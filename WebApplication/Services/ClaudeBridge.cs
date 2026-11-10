using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>Lazy loopback bridge. The worker owns runs independently of browser/HTTP lifetime.</summary>
public sealed class ClaudeBridge(IConfiguration configuration, IWebHostEnvironment environment)
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private static readonly HttpClient client = new(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false }) { Timeout = TimeSpan.FromSeconds(30) };
    private string Root => Path.Combine(RemoteToolPaths.ProgramData, "claude-bridge");
    private string FindExecutable(string setting, string name) {
        var configured = configuration["Claude:" + setting];
        if (!string.IsNullOrWhiteSpace(configured)) return File.Exists(configured) ? Path.GetFullPath(configured) : throw new InvalidOperationException($"Claude:{setting} 指向的程序不存在");
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var directories = new[] { Path.Combine(home, ".local", "bin"), "/opt/homebrew/bin", "/usr/local/bin" }
            .Concat((Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator));
        foreach (var directory in directories.Where(x => !string.IsNullOrWhiteSpace(x))) {
            var path = Path.Combine(directory, name + (OperatingSystem.IsWindows() ? ".exe" : ""));
            if (File.Exists(path)) return path;
        }
        throw new InvalidOperationException($"未找到 {name}，请安装后配置 Claude:{setting} 的绝对路径");
    }
    private async Task<(int Port, string Token)?> Existing(CancellationToken ct) {
        try {
            var status = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Root, "status.json"), ct));
            var connection = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(Root, "connection.json"), ct));
            var port = status["port"]!.GetValue<int>(); var token = connection["token"]!.GetValue<string>();
            if (port is < 1 or > 65535 || token.Length < 40) return null;
            using var request = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{port}/health");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var budget = CancellationTokenSource.CreateLinkedTokenSource(ct); budget.CancelAfter(TimeSpan.FromMilliseconds(600));
            using var result = await client.SendAsync(request, budget.Token);
            if (!result.IsSuccessStatusCode) return null;
            var health = JsonNode.Parse(await result.Content.ReadAsStringAsync(budget.Token));
            if (health?["protocol"]?.GetValue<int>() != 1) throw new InvalidOperationException("Claude 桥接版本不同，请等待任务完成后在本机更新");
            return (port, token);
        } catch (Exception e) when (e is IOException or System.Text.Json.JsonException or HttpRequestException or OperationCanceledException or NullReferenceException) {ct.ThrowIfCancellationRequested(); return null;}
    }
    private async Task<(int Port, string Token)> Connect(CancellationToken ct) {
        if (configuration.GetValue<bool?>("Claude:Enabled") == false) throw new InvalidOperationException("Claude 接入未启用，请设置 Claude:Enabled=true");
        await gate.WaitAsync(ct);
        try {
            if (await Existing(ct) is { } live) return live;
            var candidates = new[] {Path.Combine(AppContext.BaseDirectory, "claude", "server.mjs"), Path.Combine(environment.ContentRootPath, "Tools", "ClaudeBridge", "server.mjs")}.Where(File.Exists).ToArray();
            var script = candidates.FirstOrDefault(p => Directory.Exists(Path.Combine(Path.GetDirectoryName(p)!, "node_modules", "@anthropic-ai", "claude-agent-sdk"))) ?? candidates.FirstOrDefault();
            if (script == null) throw new InvalidOperationException("Claude 桥接文件未发布，请重新安装服务端");
            if (!Directory.Exists(Path.Combine(Path.GetDirectoryName(script)!, "node_modules", "@anthropic-ai", "claude-agent-sdk"))) throw new InvalidOperationException("请在服务端 claude 目录（源码 Tools/ClaudeBridge）运行 npm ci --omit=dev --omit=optional 安装官方 SDK，然后重试");
            var node = FindExecutable("NodeExecutable", "node"); var claude = FindExecutable("Executable", "claude");
            Directory.CreateDirectory(Root);
            if ((File.GetAttributes(Root) & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("Claude 数据目录不能是链接");
            if (OperatingSystem.IsWindows()) {
                var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
                var inherit = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
                foreach (var identity in new[] {WindowsIdentity.GetCurrent().User!, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null)})
                    acl.AddAccessRule(new FileSystemAccessRule(identity, FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(Root).SetAccessControl(acl);
            }
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(Root, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            var connection = Path.Combine(Root, "connection.json");
            if (!File.Exists(connection)) {
                var temporary = connection + "." + Guid.NewGuid().ToString("N") + ".tmp";
                try {
                    await File.WriteAllTextAsync(temporary, new JsonObject {["token"] = Convert.ToHexString(RandomNumberGenerator.GetBytes(32))}.ToJsonString(), ct);
                    if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                    try {File.Move(temporary, connection, false);} catch (IOException) when (File.Exists(connection)) { }
                } finally {if (File.Exists(temporary)) File.Delete(temporary);}
            }
            var start = new ProcessStartInfo(node) {UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetDirectoryName(script)!, RedirectStandardOutput = true, RedirectStandardError = true};
            start.ArgumentList.Add(script);
            start.Environment.Remove("CLAUDE_CONFIG_DIR");
            start.Environment.Remove("CLAUDECODE");
            start.Environment["REMOTETOOL_CLAUDE_STATE"] = Root;
            start.Environment["REMOTETOOL_CLAUDE_EXECUTABLE"] = claude;
            var process = Process.Start(start) ?? throw new InvalidOperationException("无法启动 Claude 桥接");
            // Drain without logging: CLI/plugins may write credentials to stderr.
            process.OutputDataReceived += (_, _) => { }; process.ErrorDataReceived += (_, _) => { };
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            for (var i = 0; i < 40; i++) {
                if (await Existing(ct) is { } ready) return ready;
                if (process.HasExited) break;
                await Task.Delay(150, ct);
            }
            throw new InvalidOperationException("Claude 桥接未启动，请检查 Node.js 20+、SDK 安装与本机 Claude Code");
        } finally {gate.Release();}
    }
    public async Task<(int Status, string Json)> Send(HttpMethod method, string path, string body, string key, CancellationToken ct) {
        var connection = await Connect(ct);
        using var request = new HttpRequestMessage(method, $"http://127.0.0.1:{connection.Port}/" + path);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", connection.Token);
        if (!string.IsNullOrWhiteSpace(key)) request.Headers.TryAddWithoutValidation("Idempotency-Key", key);
        if (body != null) request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await client.SendAsync(request, ct);
        return ((int)response.StatusCode, await response.Content.ReadAsStringAsync(ct));
    }
}
