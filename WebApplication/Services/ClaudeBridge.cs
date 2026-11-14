using System.Diagnostics;
using System.Net.Http.Headers;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Win32;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// Lazily started loopback Node worker that drives the official Claude Agent SDK.
/// The worker owns runs independently of HTTP/browser lifetime and survives host restarts.
/// </summary>
public sealed class ClaudeBridge(IConfiguration configuration, IWebHostEnvironment environment)
{
    public const int Protocol = 2;
    private readonly SemaphoreSlim gate = new(1, 1);
    // Infinite: event streams are long-lived. Ordinary calls use per-request budgets.
    private static readonly HttpClient client = new(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false }) { Timeout = Timeout.InfiniteTimeSpan };
    private static string Root => RemoteToolPaths.ClaudeBridge;
    private bool Enabled => configuration.GetValue<bool?>("Claude:Enabled") != false;

    public sealed record Health(int Port, string Token, int Protocol, int Version, int ActiveRuns, int Waiting, int Pid);

    /// <summary>Home directory of the account Claude Code must run as (the desktop user on a Windows service).</summary>
    internal static string UserProfile() {
        if (OperatingSystem.IsWindows() && InteractiveProcessLauncher.IsRunningInSession0) {
            var sid = InteractiveProcessLauncher.GetInteractiveUserSid();
            var profile = sid == null ? null : Registry.GetValue($@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\{sid}", "ProfileImagePath", null)?.ToString();
            if (!string.IsNullOrWhiteSpace(profile)) return Environment.ExpandEnvironmentVariables(profile);
        }
        return Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
    }

    internal string FindExecutable(string setting, string name) {
        var configured = configuration["Claude:" + setting];
        if (!string.IsNullOrWhiteSpace(configured)) return File.Exists(configured) ? Path.GetFullPath(configured) : throw new InvalidOperationException($"Claude:{setting} 指向的程序不存在");
        var installed = Installation()?[setting]?.ToString();
        if (!string.IsNullOrWhiteSpace(installed) && Path.IsPathFullyQualified(installed) && File.Exists(installed)) return installed;
        var home = UserProfile();
        var directories = new[] { Path.Combine(home, ".local", "bin"), "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin" }
            .Concat(OperatingSystem.IsWindows() ? new[] { Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "nodejs"), Path.Combine(home, "AppData", "Roaming", "npm") } : Array.Empty<string>())
            .Concat((Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator));
        foreach (var directory in directories.Where(x => !string.IsNullOrWhiteSpace(x))) {
            var path = Path.Combine(directory, name + (OperatingSystem.IsWindows() ? ".exe" : ""));
            if (File.Exists(path)) return path;
            if (name == "claude") {
                var native = Path.Combine(directory, "node_modules", "@anthropic-ai", "claude-code", "bin", "claude.exe");
                if (OperatingSystem.IsWindows() && File.Exists(native)) return native;
            }
        }
        throw new InvalidOperationException($"未找到 {name}，请安装后配置 Claude:{setting} 的绝对路径");
    }

    private static JsonObject? Installation() {
        try { return JsonNode.Parse(File.ReadAllText(Path.Combine(RemoteToolPaths.ClaudeManagedTools, "installation.json"))) as JsonObject; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException) { return null; }
    }
    internal bool PendingRestart => Installation()?["restart_pending"]?.ToString() == "true";
    internal string ResolveScript(bool requireSdk = true) {
        var installed = Installation();
        var script = installed?["script"]?.ToString();
        if (installed?["bridge_version"]?.ToString() == RemoteToolPaths.ClaudeBridgeVersion.ToString() && !string.IsNullOrWhiteSpace(script)
            && Path.IsPathFullyQualified(script) && File.Exists(script) && (!requireSdk || SdkInstalled(script))) return script;
        return FindScript(AppContext.BaseDirectory, environment.ContentRootPath, requireSdk);
    }
    internal static bool SdkInstalled(string script) {
        var sdk = Path.Combine(Path.GetDirectoryName(script)!, "node_modules", "@anthropic-ai", "claude-agent-sdk");
        return File.Exists(Path.Combine(sdk, "package.json")) && File.Exists(Path.Combine(sdk, "sdk.mjs"));
    }
    internal static string FindScript(string baseDirectory, string contentRoot, bool requireSdk = true) {
        var candidates = new List<string>();
        foreach (var root in new[] { baseDirectory, contentRoot }) {
            candidates.Add(Path.Combine(root, "claude", "server.mjs"));
            candidates.Add(Path.Combine(root, "Tools", "ClaudeBridge", "server.mjs"));
        }
        foreach (var root in new[] { baseDirectory, contentRoot }) {
            for (var directory = new DirectoryInfo(root); directory != null; directory = directory.Parent) {
                if (!File.Exists(Path.Combine(directory.FullName, "WebApplication.csproj"))) continue;
                candidates.Add(Path.Combine(directory.FullName, "Tools", "ClaudeBridge", "server.mjs"));
                break;
            }
        }
        var comparer = OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
        var scripts = candidates.Distinct(comparer).Where(File.Exists).ToArray();
        var script = scripts.FirstOrDefault(SdkInstalled) ?? scripts.FirstOrDefault()
            ?? throw new InvalidOperationException("Claude 桥接文件未发布，请重新安装服务端");
        if (requireSdk && !SdkInstalled(script)) throw new InvalidOperationException($"请在 Claude 桥接目录 {Path.GetDirectoryName(script)} 执行 npm ci --omit=dev --omit=optional 安装官方 SDK，然后重试");
        return script;
    }

    private static async Task<HttpResponseMessage> Raw(HttpMethod method, int port, string token, string path, string? body, string? key, string? lastEvent, TimeSpan? budget, CancellationToken ct) {
        using var request = new HttpRequestMessage(method, $"http://127.0.0.1:{port}/{path}");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (!string.IsNullOrWhiteSpace(key)) request.Headers.TryAddWithoutValidation("Idempotency-Key", key);
        if (!string.IsNullOrWhiteSpace(lastEvent)) request.Headers.TryAddWithoutValidation("Last-Event-ID", lastEvent);
        if (body != null) request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        if (budget == null) return await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(budget.Value);
        var response = await client.SendAsync(request, HttpCompletionOption.ResponseContentRead, timeout.Token);
        return response;
    }

    /// <summary>Reads the running worker's health without ever starting one.</summary>
    public async Task<Health?> Probe(CancellationToken ct) {
        try {
            var status = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Root, "status.json"), ct));
            var connection = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Root, "connection.json"), ct));
            var port = status?["port"]?.GetValue<int>() ?? 0; var token = connection?["token"]?.GetValue<string>() ?? "";
            if (port is < 1 or > 65535 || token.Length < 40) return null;
            using var response = await Raw(HttpMethod.Get, port, token, "health", null, null, null, TimeSpan.FromSeconds(3), ct);
            if (!response.IsSuccessStatusCode) return null;
            var health = JsonNode.Parse(await response.Content.ReadAsStringAsync(ct));
            return new Health(port, token, health?["protocol"]?.GetValue<int>() ?? 0, health?["version"]?.GetValue<int>() ?? 0,
                health?["active_runs"]?.GetValue<int>() ?? 0, health?["waiting"]?.GetValue<int>() ?? 0, health?["pid"]?.GetValue<int>() ?? 0);
        } catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Text.Json.JsonException or HttpRequestException or OperationCanceledException or InvalidOperationException or FormatException) {
            ct.ThrowIfCancellationRequested(); return null;
        }
    }

    /// <summary>True when the worker file says a process exists but it does not answer: idleness is unproven.</summary>
    public static bool UnansweredWorker() {
        try {
            var status = JsonNode.Parse(File.ReadAllText(Path.Combine(Root, "status.json")));
            var pid = status?["pid"]?.GetValue<int>() ?? 0;
            if (pid <= 0) return false;
            using var process = Process.GetProcessById(pid);
            return !process.HasExited && process.ProcessName.Contains("node", StringComparison.OrdinalIgnoreCase);
        } catch (Exception e) when (e is IOException or ArgumentException or InvalidOperationException or System.Text.Json.JsonException or UnauthorizedAccessException) { return false; }
    }

    private async Task<Health> Connect(CancellationToken ct) {
        if (!Enabled) throw new InvalidOperationException("Claude 接入未启用，请设置 Claude:Enabled=true");
        await gate.WaitAsync(ct);
        try {
            if (await Probe(ct) is { } live) {
                if (live.Protocol == Protocol && live.Version >= RemoteToolPaths.ClaudeBridgeVersion && !PendingRestart) return live;
                // Never interrupt running or waiting tasks to upgrade; an idle old worker exits on request.
                if (live.ActiveRuns > 0 || live.Waiting > 0) {
                    if (live.Protocol == Protocol) return live;
                    throw new InvalidOperationException("Claude 桥接正在执行旧版本任务；任务结束后会自动更新，请稍后重试");
                }
                using (var stop = await Raw(HttpMethod.Post, live.Port, live.Token, "shutdown", "{}", null, null, TimeSpan.FromSeconds(3), ct)) {
                    // Protocol 1 workers predate /shutdown; they reported zero active runs above, so ending them loses nothing.
                    if (stop.StatusCode == System.Net.HttpStatusCode.NotFound && live.Protocol < Protocol && live.Pid > 0) {
                        try { using var old = Process.GetProcessById(live.Pid); if (old.ProcessName.Contains("node", StringComparison.OrdinalIgnoreCase)) old.Kill(); }
                        catch (ArgumentException) { }
                    } else if (!stop.IsSuccessStatusCode) throw new InvalidOperationException("Claude 桥接正在执行任务；结束后会自动更新，请稍后重试");
                }
                for (var i = 0; i < 40 && await Probe(ct) != null; i++) await Task.Delay(100, ct);
            }
            var script = ResolveScript();
            var node = FindExecutable("NodeExecutable", "node");
            var claude = FindExecutable("Executable", "claude");
            Directory.CreateDirectory(Root);
            if ((File.GetAttributes(Root) & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("Claude 数据目录不能是链接");
            var attachments = RemoteToolPaths.Attachments("claude");
            Directory.CreateDirectory(attachments);
            if (OperatingSystem.IsWindows()) {
                var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
                var inherit = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
                var owners = new List<SecurityIdentifier> { WindowsIdentity.GetCurrent().User!, new(WellKnownSidType.LocalSystemSid, null) };
                if (InteractiveProcessLauncher.IsRunningInSession0 && InteractiveProcessLauncher.GetInteractiveUserSid() is { } sid) owners.Add(new SecurityIdentifier(sid));
                foreach (var identity in owners.Distinct())
                    acl.AddAccessRule(new FileSystemAccessRule(identity, FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(Root).SetAccessControl(acl);
                new DirectoryInfo(attachments).SetAccessControl(acl);
            } else File.SetUnixFileMode(Root, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            var connection = Path.Combine(Root, "connection.json");
            if (!File.Exists(connection)) {
                var temporary = connection + "." + Guid.NewGuid().ToString("N") + ".tmp";
                try {
                    await File.WriteAllTextAsync(temporary, new JsonObject { ["token"] = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)) }.ToJsonString(), ct);
                    if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                    try { File.Move(temporary, connection, false); } catch (IOException) when (File.Exists(connection)) { }
                } finally { if (File.Exists(temporary)) File.Delete(temporary); }
            }
            // A live but slow worker keeps its tasks; never start a competitor next to it.
            if (UnansweredWorker()) {
                for (var i = 0; i < 20; i++) { await Task.Delay(250, ct); if (await Probe(ct) is { } late) return late; }
                throw new InvalidOperationException("Claude 桥接进程无响应；为保护运行中的任务未自动重启，请稍后重试或在服务端检查");
            }
            var arguments = new List<string> { script, "--state", Root, "--claude", claude, "--attachments", attachments };
            if (OperatingSystem.IsWindows() && InteractiveProcessLauncher.IsRunningInSession0) {
                // Same model as Codex: run as the signed-in desktop user, who owns the Claude login.
                var command = string.Join(" ", new[] { node }.Concat(arguments).Select(value => "\"" + value.Replace("\"", "\\\"") + "\""));
                var launch = InteractiveProcessLauncher.LaunchInInteractiveSession(command, Path.GetDirectoryName(script));
                if (!launch.Success) throw new InvalidOperationException("无法在桌面用户会话中启动 Claude Code，请先登录电脑桌面");
            } else {
                // No redirected pipes: the worker must outlive this host, and a closed pipe must not kill it.
                var start = new ProcessStartInfo(node) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetDirectoryName(script)! };
                start.Environment["PATH"] = Path.GetDirectoryName(node) + Path.PathSeparator + Environment.GetEnvironmentVariable("PATH");
                foreach (var value in arguments) start.ArgumentList.Add(value);
                // Session markers inherited from a Claude terminal would make runs child sessions without transcripts.
                foreach (var name in new[] { "CLAUDE_CONFIG_DIR", "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SSE_PORT", "CLAUDE_CODE_SESSION_ID",
                    "CLAUDE_PID", "CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN", "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_EXECPATH" }) start.Environment.Remove(name);
                using var process = Process.Start(start) ?? throw new InvalidOperationException("无法启动 Claude 桥接");
            }
            for (var i = 0; i < 60; i++) {
                await Task.Delay(150, ct);
                if (await Probe(ct) is { } ready && ready.Protocol == Protocol) {
                    if (Installation() is { } installed && installed["restart_pending"]?.ToString() == "true") {
                        installed["restart_pending"] = false;
                        Codex.CodexJson.Atomic(Path.Combine(RemoteToolPaths.ClaudeManagedTools, "installation.json"), installed);
                    }
                    return ready;
                }
            }
            throw new InvalidOperationException("Claude 桥接未启动，请检查 Node.js 20+、SDK 安装与本机 Claude Code");
        } finally { gate.Release(); }
    }

    /// <summary>Forwards to the worker. Streams (SSE) keep the response open; other calls use a bounded budget.</summary>
    public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, string? body, CancellationToken ct, string? key = null, string? lastEvent = null, bool stream = false) {
        var health = await Connect(ct);
        // Run starts include a login/ownership preflight; give them longer than reads.
        var budget = stream ? (TimeSpan?)null : method == HttpMethod.Get ? TimeSpan.FromSeconds(30) : TimeSpan.FromSeconds(90);
        return await Raw(method, health.Port, health.Token, path, body, key, lastEvent, budget, ct);
    }
}
