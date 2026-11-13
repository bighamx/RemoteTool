using System.Diagnostics;
using System.Formats.Tar;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using RemoteTool.WebApi.Services.RemoteControl;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services;

public sealed class ClaudeInstaller
{
    public sealed record Status(string Id, string Phase, string Message) {
        public bool Busy => Phase is "checking" or "installing_node" or "installing_cli" or "installing_sdk" or "validating";
    }
    private readonly string root;
    private readonly IConfiguration configuration;
    private readonly ClaudeBridge bridge;
    private readonly HttpClient http;
    private readonly Func<string, string[], CancellationToken, Task<string>> run;
    private readonly Func<CancellationToken, Task<bool>> validate;
    private readonly Func<string, string, string?> locate;
    private readonly CancellationToken stopping;
    private readonly object gate = new();
    private Status status = new("", "idle", "");
    internal Task Completion { get; private set; } = Task.CompletedTask;
    public Status Current { get { lock (gate) return status; } }
    public bool CanConfigure => configuration.GetValue<bool?>("Claude:Enabled") != false
        && (OperatingSystem.IsWindows() || OperatingSystem.IsLinux() || OperatingSystem.IsMacOS())
        && RuntimeInformation.ProcessArchitecture is Architecture.X64 or Architecture.Arm64;
    public ClaudeInstaller(IHostApplicationLifetime lifetime, IConfiguration configuration, ClaudeBridge bridge, IHttpClientFactory clients)
        : this(RemoteToolPaths.ClaudeManagedTools, configuration, bridge, clients.CreateClient("claude-install"), Run,
            async ct => {
                using var response = await bridge.SendAsync(HttpMethod.Get, "setup", null, ct);
                if (!response.IsSuccessStatusCode) throw new IOException("Claude 桥接验证失败，请重新检测。");
                return JsonNode.Parse(await response.Content.ReadAsStringAsync(ct))?["ready"]?.GetValue<bool>() == true;
            }, lifetime.ApplicationStopping) { }
    internal ClaudeInstaller(string root, IConfiguration configuration, ClaudeBridge bridge, HttpClient http,
        Func<string, string[], CancellationToken, Task<string>> run, Func<CancellationToken, Task<bool>> validate, CancellationToken stopping = default,
        Func<string, string, string?>? locate = null) {
        this.root = Path.GetFullPath(root); this.configuration = configuration; this.bridge = bridge;
        this.http = http; this.http.Timeout = TimeSpan.FromMinutes(20); this.run = run; this.validate = validate; this.stopping = stopping;
        this.locate = locate ?? bridge.FindExecutable;
        try {
            var path = Path.Combine(root, "status.json");
            if (File.Exists(path)) status = JsonSerializer.Deserialize<Status>(File.ReadAllText(path), new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? status;
            if (status.Busy) status = status with { Phase = "failed", Message = "服务已重启，配置结果待检测。可重新点击一键配置。" };
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { }
    }
    public Status Start() {
        lock (gate) {
            if (status.Busy) return status;
            if (!CanConfigure) throw new InvalidOperationException("当前平台或 Claude 配置不支持自动配置。");
            Directory.CreateDirectory(root);
            Update(new(Guid.NewGuid().ToString("N"), "checking", "正在检查 Node.js、Claude Code 和 SDK。"));
            var id = status.Id;
            Completion = Task.Run(() => Configure(id));
            return status;
        }
    }
    private void Update(Status next) {
        lock (gate) {
            status = next;
            try { Atomic(Path.Combine(root, "status.json"), JsonSerializer.SerializeToNode(next)); }
            catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
    private string? Existing(string setting, string name) {
        try { return locate(setting, name); }
        catch (InvalidOperationException) when (string.IsNullOrWhiteSpace(configuration["Claude:" + setting])) { return null; }
    }
    private static string? Npm(string node) {
        var folder = Path.GetDirectoryName(node)!;
        var candidates = new[] { Path.Combine(folder, "node_modules", "npm", "bin", "npm-cli.js"),
            Path.GetFullPath(Path.Combine(folder, "..", "lib", "node_modules", "npm", "bin", "npm-cli.js")),
            Path.Combine(folder, "npm") };
        foreach (var candidate in candidates.Where(File.Exists)) {
            var resolved = new FileInfo(candidate).ResolveLinkTarget(true)?.FullName ?? candidate;
            if (resolved.EndsWith(".js", StringComparison.OrdinalIgnoreCase)) return resolved;
        }
        return null;
    }
    internal static string? LocateCli(string prefix) {
        var package = Path.Combine(prefix, OperatingSystem.IsWindows() ? "node_modules" : "lib/node_modules", "@anthropic-ai", "claude-code");
        if (!Directory.Exists(package)) return null;
        var name = OperatingSystem.IsWindows() ? "claude.exe" : "claude";
        return Directory.EnumerateFiles(package, name, new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint })
            .OrderBy(path => path.Contains(Path.DirectorySeparatorChar + "bin" + Path.DirectorySeparatorChar) ? 0 : 1).FirstOrDefault();
    }
    private async Task<bool> SupportsCli(string cli, CancellationToken ct) {
        try {
            var version = await run(cli, ["--version"], ct);
            return version.Contains("Claude", StringComparison.OrdinalIgnoreCase) && Regex.IsMatch(version, @"\d+\.\d+\.\d+")
                && (await run(cli, ["agents", "--help"], ct)).Contains("--json")
                && (await run(cli, ["auth", "status", "--help"], ct)).Contains("--json");
        } catch (Exception error) when (error is IOException or System.ComponentModel.Win32Exception) { return false; }
    }
    private async Task<bool> SupportsSdk(string node, string script, CancellationToken ct) {
        var sdkUrl = new Uri(Path.Combine(Path.GetDirectoryName(script)!, "node_modules", "@anthropic-ai", "claude-agent-sdk", "sdk.mjs")).AbsoluteUri;
        try {
            await run(node, ["--input-type=module", "-e", "const s=await import(process.argv[1]);if(typeof s.query!=='function'||typeof s.listSessions!=='function')process.exit(1)", sdkUrl], ct);
            return true;
        } catch (Exception error) when (error is IOException or System.ComponentModel.Win32Exception) { return false; }
    }
    private async Task Configure(string id) {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stopping);
        timeout.CancelAfter(TimeSpan.FromMinutes(20));
        var ct = timeout.Token;
        var staging = Path.Combine(root, "staging-" + id);
        try {
            using var lease = new FileStream(Path.Combine(root, "install.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
            GrantDesktopReadAccess();
            var node = Existing("NodeExecutable", "node");
            var version = node == null ? "" : await run(node, ["--version"], ct);
            var current = Regex.Match(version, @"v(\d+)\.");
            if (node == null || !int.TryParse(current.Groups[1].Value, out var major) || major < 22 || Npm(node) == null) {
                if (!string.IsNullOrWhiteSpace(configuration["Claude:NodeExecutable"])) throw new InvalidOperationException("指定的 Claude:NodeExecutable 需要 Node.js 22+ 和 npm。请修正该配置后重试。");
                Update(new(id, "installing_node", "正在下载并校验 Node.js 运行时。"));
                node = await InstallNode(staging, ct);
            }
            var npm = Npm(node) ?? throw new IOException("Node.js 缺少 npm，请重新配置。");
            var cli = Existing("Executable", "claude");
            if (cli != null && !await SupportsCli(cli, ct)) {
                if (!string.IsNullOrWhiteSpace(configuration["Claude:Executable"])) throw new InvalidOperationException("指定的 Claude:Executable 缺少所需命令，请更新该程序或修正路径后重试。");
                cli = null;
            }
            string? cliPrefix = null;
            if (cli == null) {
                Update(new(id, "installing_cli", "正在安装官方 Claude Code。"));
                cliPrefix = Path.Combine(staging, "cli");
                await run(node, [npm, "install", "--global", "--prefix", cliPrefix, "--registry", "https://registry.npmjs.org", "--no-audit", "--no-fund", "@anthropic-ai/claude-code@latest"], ct);
                cli = LocateCli(cliPrefix) ?? throw new IOException("Claude Code 安装后未找到可执行程序。");
            }
            Update(new(id, "validating", "正在验证 Claude Code 版本。"));
            if (!await SupportsCli(cli, ct)) throw new IOException("Claude Code 版本或所需命令验证失败。");
            var script = bridge.ResolveScript(false);
            string? sdkFolder = null;
            if (!ClaudeBridge.SdkInstalled(script) || !await SupportsSdk(node, script, ct)) {
                Update(new(id, "installing_sdk", "正在安装 Claude 官方 SDK。"));
                sdkFolder = Path.Combine(staging, "bridge");
                Directory.CreateDirectory(sdkFolder);
                foreach (var file in Directory.EnumerateFiles(Path.GetDirectoryName(script)!).Where(file => file.EndsWith(".mjs") || Path.GetFileName(file) is "package.json" or "package-lock.json"))
                    File.Copy(file, Path.Combine(sdkFolder, Path.GetFileName(file)), true);
                await run(node, [npm, "--prefix", sdkFolder, "ci", "--omit=dev", "--omit=optional", "--registry", "https://registry.npmjs.org", "--no-audit", "--no-fund"], ct);
                script = Path.Combine(sdkFolder, "server.mjs");
                if (!await SupportsSdk(node, script, ct)) throw new IOException("SDK 安装验证失败，请重新配置。");
            }
            Update(new(id, "validating", "正在验证 SDK 和桥接连接。"));
            if (cliPrefix != null) {
                var target = Path.Combine(root, "cli-" + id);
                var relative = Path.GetRelativePath(cliPrefix, cli);
                Directory.Move(cliPrefix, target); cli = Path.Combine(target, relative);
            }
            if (sdkFolder != null) {
                var target = Path.Combine(root, "bridge-" + id);
                Directory.Move(sdkFolder, target); script = Path.Combine(target, "server.mjs");
            }
            var manifest = Path.Combine(root, "installation.json");
            if (File.Exists(manifest)) File.Copy(manifest, manifest + ".previous", true);
            Atomic(manifest, Obj(("NodeExecutable", node), ("Executable", cli), ("script", script), ("bridge_version", RemoteToolPaths.ClaudeBridgeVersion), ("restart_pending", true)));
            var signedIn = await validate(ct);
            Update(new(id, "ready", bridge.PendingRestart ? "Claude 组件配置完成。" : signedIn ? "Claude 配置完成，桥接验证通过。" : "Claude 配置完成。请在服务端用户下执行 claude auth login 登录，然后重新检测。"));
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException or HttpRequestException or OperationCanceledException or System.ComponentModel.Win32Exception or JsonException) {
            var message = error is OperationCanceledException ? "配置已超时或服务已停止，可重新检测后重试。"
                : error is UnauthorizedAccessException ? "配置目录权限不足，请检查服务账户权限后重试。"
                : error is HttpRequestException ? "下载未完成，请检查服务端网络后重试。" : error.Message;
            Update(new(id, "failed", message));
        } finally {
            if (Directory.Exists(staging)) try { Directory.Delete(staging, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
    private void GrantDesktopReadAccess() {
        if (!OperatingSystem.IsWindows() || !InteractiveProcessLauncher.IsRunningInSession0) return;
        var acl = new DirectoryInfo(root).GetAccessControl();
        acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(InteractiveProcessLauncher.GetInteractiveUserSid()), FileSystemRights.ReadAndExecute,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(root).SetAccessControl(acl);
    }
    private async Task<string> InstallNode(string staging, CancellationToken ct) {
        var os = OperatingSystem.IsWindows() ? "win" : OperatingSystem.IsMacOS() ? "darwin" : "linux";
        var arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64";
        var suffix = OperatingSystem.IsWindows() ? "zip" : "tar.gz";
        foreach (var existing in Directory.EnumerateDirectories(root, "node-v24.*-" + os + "-" + arch + "*").OrderByDescending(Directory.GetLastWriteTimeUtc)) {
            var executable = Path.Combine(existing, OperatingSystem.IsWindows() ? "node.exe" : "bin/node");
            if (!File.Exists(executable) || Npm(executable) == null) continue;
            try { if (Regex.IsMatch(await run(executable, ["--version"], ct), @"^v24\.")) return executable; }
            catch (Exception error) when (error is IOException or System.ComponentModel.Win32Exception) { }
        }
        var checksums = await http.GetStringAsync("https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt", ct);
        var match = Regex.Match(checksums, @"(?m)^([a-fA-F0-9]{64})\s+(node-v24\.\d+\.\d+-" + os + "-" + arch + @"\." + Regex.Escape(suffix) + @")\s*$");
        if (!match.Success) throw new IOException("官方 Node.js 运行时清单不包含当前平台。");
        var name = match.Groups[2].Value;
        Directory.CreateDirectory(staging);
        var archive = Path.Combine(staging, name);
        using (var source = await http.GetStreamAsync("https://nodejs.org/dist/latest-v24.x/" + name, ct))
        await using (var output = File.Create(archive)) { await source.CopyToAsync(output, ct); }
        await using (var input = File.OpenRead(archive)) {
            if (!Convert.ToHexString(await SHA256.HashDataAsync(input, ct)).Equals(match.Groups[1].Value, StringComparison.OrdinalIgnoreCase)) throw new IOException("Node.js 下载校验失败，请重试。");
        }
        var extracted = Path.Combine(staging, "node"); Directory.CreateDirectory(extracted);
        if (OperatingSystem.IsWindows()) ZipFile.ExtractToDirectory(archive, extracted);
        else { await using var input = File.OpenRead(archive); await using var gzip = new GZipStream(input, CompressionMode.Decompress); await TarFile.ExtractToDirectoryAsync(gzip, extracted, false, ct); }
        var folderName = name[..^suffix.Length].TrimEnd('.');
        var target = Path.Combine(root, folderName);
        if (Directory.Exists(target)) target += "-" + Guid.NewGuid().ToString("N");
        Directory.Move(Path.Combine(extracted, folderName), target);
        var node = Path.Combine(target, OperatingSystem.IsWindows() ? "node.exe" : "bin/node");
        if (!File.Exists(node) || Npm(node) == null || !Regex.IsMatch(await run(node, ["--version"], ct), @"v24\.")) throw new IOException("Node.js 安装验证失败。");
        return node;
    }
    private static async Task<string> Run(string file, string[] args, CancellationToken ct) {
        var start = new ProcessStartInfo(file) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
        start.Environment["PATH"] = Path.GetDirectoryName(file) + Path.PathSeparator + Environment.GetEnvironmentVariable("PATH");
        foreach (var arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start) ?? throw new IOException("无法启动配置程序。"); process.StandardInput.Close();
        var output = Read(process.StandardOutput); var errors = Read(process.StandardError);
        try { await process.WaitForExitAsync(ct); } catch { if (!process.HasExited) process.Kill(true); throw; }
        var text = await output; await errors;
        if (process.ExitCode != 0) throw new IOException("配置命令执行失败，请检查服务端网络、程序版本和目录权限。");
        return text.Trim();
    }
    private static async Task<string> Read(StreamReader reader) {
        var text = new StringBuilder(); var buffer = new char[2048]; int count;
        while ((count = await reader.ReadAsync(buffer)) > 0) if (text.Length < 65536) text.Append(buffer, 0, Math.Min(count, 65536 - text.Length));
        return text.ToString();
    }
}
