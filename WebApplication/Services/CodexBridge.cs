using System.Diagnostics;
using System.Net.Http.Headers;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;
using Microsoft.Win32;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services;

public sealed class CodexBridge(IHttpClientFactory clients, IConfiguration configuration) : ITitleModelGateway
{
    public async Task<JsonObject> WorkspaceResetCreditDetails(string workspaceId, CancellationToken ct) {
        await Ensure(ct);
        // The HTTP host can use the system's network route even when an isolated CLI
        // cannot reach ChatGPT. Read the exact workspace credentials under its store lock.
        var config = Read(Path.Combine(folder, "connection.json"));
        var home = config.S("home");
        var store = config.S("account_store", Path.Combine(Path.GetDirectoryName(home) ?? home, ".codex-switch"));
        var accounts = new Codex.CodexAccountStore(home, store);
        var auth = accounts.WorkspaceAuth(workspaceId);
        var snapshot = await Codex.CodexWorkspaceUsage.Read(config.S("executable"), config.S("state_folder", folder), auth, ct);
        accounts.RecoverWorkspaceAuth(workspaceId, auth, snapshot.Auth);
        var usage = snapshot.Usage;
        if (usage["rateLimitResetCredits"].L("availableCount") > 0)
            usage = await Codex.CodexWorkspaceUsage.ReadResetCreditDetails(snapshot.Auth, usage, ct);
        return Obj(("workspace_id", workspaceId), ("chatgpt_account_id", Codex.CodexAccountStore.Identity(snapshot.Auth).S("workspace_id")),
            ("available", true), ("usage", usage), ("checked_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
    }
    public async Task<JsonObject> TitleRequest(string path, JsonObject body, CancellationToken ct) {
        using var response = await SendAsync(HttpMethod.Post, path, JsonSerializer.SerializeToElement(body), ct);
        var value = JsonNode.Parse(await response.Content.ReadAsStringAsync(ct))!.AsObject();
        if (!response.IsSuccessStatusCode) throw new Codex.CodexError(value.S("message", "标题模型请求失败"), (int)response.StatusCode, value.S("code"));
        return value;
    }
    private readonly SemaphoreSlim startup = new(1, 1);
    private readonly string folder = RemoteToolPaths.CodexBridge;
    private string token;
    private int port;

    private async Task<bool> Ready(CancellationToken ct) {
        if (port <= 0 || token == null) return false;
        try {
            using var request = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{port}/health");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(2));
            using var response = await clients.CreateClient("codex").SendAsync(request, timeout.Token);
            if (!response.IsSuccessStatusCode) return false;
            using var status = JsonDocument.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
            return status.RootElement.TryGetProperty("implementation", out var implementation) && implementation.GetString() == "dotnet-v2" &&
                status.RootElement.TryGetProperty("state_version", out var version) && version.GetInt32() >= 14;
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return false; }
        catch (HttpRequestException) { return false; }
        catch (JsonException) { return false; }
    }
    private async Task<JsonObject> CaptureHandoff(string statusPath, CancellationToken ct) {
        if (port <= 0 || token == null || !File.Exists(statusPath)) return null;
        try {
            async Task<JsonObject> Get(string path) {
                using var request = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{port}/{path}");
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(5));
                using var response = await clients.CreateClient("codex").SendAsync(request, timeout.Token);
                response.EnsureSuccessStatusCode();
                return JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token))!.AsObject();
            }
            var health = await Get("health");
            if (health.S("implementation") != "dotnet-v2" || health.L("state_version") >= 14) return null;
            var saved = Read(statusPath);
            using var worker = Process.GetProcessById((int)saved.L("pid"));
            using var cli = Process.GetProcessById((int)health.L("cli_pid"));
            if (worker.ProcessName != "dotnet" || cli.ProcessName != "codex") return null;
            var active = new JsonObject();
            var prior = Read(Path.Combine(folder, "connection.json"));
            var journal = Read(Path.Combine(prior.S("state_folder", folder), "runs.json"));
            var candidates = journal.Where(entry => entry.Value.S("status") is "started" or "submitting").Select(entry => entry.Key)
                .Concat((prior["handoff"]?["runs"] as JsonObject ?? new()).Select(entry => entry.Key)).Distinct();
            foreach (var run in candidates) {
                var state = await Get("runs/" + run);
                if (state.S("status") is not ("started" or "submitting")) continue;
                var id = state.S("session_id");
                var info = await Get("sessions/" + id);
                if (info["session"]?["status"].S("type") == "active") active[run] = id;
            }
            return Obj(("port", port), ("pid", worker.Id), ("process_started_ticks", worker.StartTime.ToUniversalTime().Ticks),
                ("cli_pid", cli.Id), ("cli_started_ticks", cli.StartTime.ToUniversalTime().Ticks), ("runs", active));
        } catch (Exception error) when (!ct.IsCancellationRequested && error is IOException or HttpRequestException or OperationCanceledException or JsonException or ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception) { return null; }
    }
    private async Task Ensure(CancellationToken ct) {
        if (await Ready(ct)) return;
        await startup.WaitAsync(ct);
        try {
            var sid = InteractiveProcessLauncher.GetInteractiveUserSid();
            Directory.CreateDirectory(folder);
            if (OperatingSystem.IsWindows()) {
                var acl = new DirectorySecurity();
                acl.SetAccessRuleProtection(true, false);
                foreach (var account in new[] { sid, "S-1-5-18" }.Distinct())
                    acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(account), FileSystemRights.FullControl,
                        InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(folder).SetAccessControl(acl);
                var attachments = Path.Combine(Path.GetDirectoryName(folder)!, "codex-attachments");
                Directory.CreateDirectory(attachments);
                new DirectoryInfo(attachments).SetAccessControl(acl);
            }
            var configPath = Path.Combine(folder, "connection.json");
            var statusPath = Path.Combine(folder, "status.json");
            if (File.Exists(configPath)) { using var saved = JsonDocument.Parse(await File.ReadAllTextAsync(configPath, ct)); token = saved.RootElement.GetProperty("token").GetString(); }
            if (File.Exists(statusPath)) {
                try { using var saved = JsonDocument.Parse(await File.ReadAllTextAsync(statusPath, ct)); port = saved.RootElement.GetProperty("port").GetInt32(); } catch (JsonException) { }
                if (await Ready(ct)) return;
            }
            var handoff = await CaptureHandoff(statusPath, ct);
            var priorState = Read(configPath).S("state_folder", folder);
            var stateFolder = Path.Combine(folder, "state-v10");
            if (!Directory.Exists(stateFolder)) {
                Directory.CreateDirectory(stateFolder);
                foreach (var name in new[] { "runs.json", "providers.json", "model-selections.json" })
                    if (File.Exists(Path.Combine(priorState, name))) File.Copy(Path.Combine(priorState, name), Path.Combine(stateFolder, name));
            }
            var configuredHome = configuration["Codex:Home"];
            var profile = !string.IsNullOrWhiteSpace(configuredHome)
                ? Path.GetDirectoryName(Path.GetFullPath(Environment.ExpandEnvironmentVariables(configuredHome)).TrimEnd(Path.DirectorySeparatorChar))
                : OperatingSystem.IsWindows()
                ? Registry.GetValue($@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\{sid}", "ProfileImagePath", null)?.ToString()
                : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (!string.IsNullOrWhiteSpace(profile)) profile = Environment.ExpandEnvironmentVariables(profile);
            // Only recover a missing/service profile. A valid RDP user's CLI installation
            // must not cause us to select another logged-on user's desktop profile.
            if (string.IsNullOrWhiteSpace(configuredHome) && OperatingSystem.IsWindows() && (string.IsNullOrWhiteSpace(profile) || profile.Contains("systemprofile", StringComparison.OrdinalIgnoreCase)))
                profile = Registry.Users.GetSubKeyNames()
                    .Select(sub => Registry.GetValue($@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\{sub}", "ProfileImagePath", null)?.ToString())
                    .Where(path => !string.IsNullOrWhiteSpace(path))
                    .Select(path => Environment.ExpandEnvironmentVariables(path!))
                    .Where(path => Directory.Exists(Path.Combine(path, "AppData", "Local", "OpenAI", "Codex", "bin")))
                    .OrderByDescending(path => Directory.GetLastWriteTimeUtc(Path.Combine(path!, "AppData", "Local", "OpenAI", "Codex")))
                    .FirstOrDefault() ?? profile;
            if (string.IsNullOrWhiteSpace(profile)) profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (string.IsNullOrWhiteSpace(profile)) throw new InvalidOperationException("无法确定 Codex 用户目录，请检查电脑登录状态");
            string ConfiguredPath(string key, string fallback) => Path.GetFullPath(Environment.ExpandEnvironmentVariables(
                string.IsNullOrWhiteSpace(configuration[key]) ? fallback : configuration[key]!));
            var accountStore = ConfiguredPath("Codex:AccountStore", Path.Combine(profile, ".codex-switch"));
            Directory.CreateDirectory(accountStore);
            if (OperatingSystem.IsWindows()) {
                var accountAcl = new DirectorySecurity();
                accountAcl.SetAccessRuleProtection(true, false);
                foreach (var account in new[] { sid, "S-1-5-18" }.Distinct()) accountAcl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(account), FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(accountStore).SetAccessControl(accountAcl);
            }
            var home = ConfiguredPath("Codex:Home", Path.Combine(profile, ".codex"));
            var executable = configuration["Codex:Executable"];
            if (!Codex.CodexExecutable.Complete(executable)) {
                // The desktop app rotates bin/<hash> directories on update; a fresh download may
                // briefly contain only codex.exe. Prefer directories that also carry the tool
                // executables (code-mode host etc.) — a lone codex.exe cannot run tools.
                var bundled = Path.Combine(profile, "AppData", "Local", "OpenAI", "Codex", "bin");
                executable = Directory.Exists(bundled)
                    ? Directory.EnumerateFiles(bundled, "codex.exe", SearchOption.AllDirectories)
                        .Where(Codex.CodexExecutable.Complete)
                        .OrderByDescending(File.GetLastWriteTimeUtc)
                        .FirstOrDefault()
                    : null;
            }
            executable = Codex.CodexExecutable.Resolve(executable, home);
            var dll = InteractiveProcessLauncher.GetApplicationDllPath();
            token ??= Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            await File.WriteAllTextAsync(configPath, JsonSerializer.Serialize(new { token, executable, home, account_store = accountStore, state_folder = stateFolder, handoff,
                working_directory = ConfiguredPath("Codex:WorkingDirectory", Path.Combine(profile, "Documents", "Codex", "Mobile")),
                attachments = Path.Combine(Path.GetDirectoryName(folder)!, "codex-attachments") }), ct);
            var command = $"dotnet \"{dll}\" --codex-bridge \"{configPath}\"";
            var launch = InteractiveProcessLauncher.LaunchInInteractiveSession(command, Path.GetDirectoryName(dll));
            if (!launch.Success) throw new InvalidOperationException("无法在桌面用户会话中启动 Codex，请先登录电脑桌面");
            for (var i = 0; i < 80; i++) {
                await Task.Delay(250, ct);
                if (File.Exists(statusPath)) {
                    try { using var saved = JsonDocument.Parse(await File.ReadAllTextAsync(statusPath, ct)); port = saved.RootElement.GetProperty("port").GetInt32(); } catch (JsonException) { continue; }
                    if (await Ready(ct)) return;
                }
            }
            throw new InvalidOperationException("Codex 桥接启动超时，请检查本机 Codex 配置和登录状态");
        } finally { startup.Release(); }
    }

    public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, JsonElement? body, CancellationToken ct, string idempotency = null, string lastEvent = null) {
        await Ensure(ct);
        using var request = new HttpRequestMessage(method, new Uri($"http://127.0.0.1:{port}/" + path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (idempotency != null) request.Headers.Add("Idempotency-Key", idempotency);
        if (lastEvent != null) request.Headers.Add("Last-Event-ID", lastEvent);
        if (body != null) request.Content = new StringContent(body.Value.GetRawText(), Encoding.UTF8, "application/json");
        return await clients.CreateClient("codex").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
    }
}
