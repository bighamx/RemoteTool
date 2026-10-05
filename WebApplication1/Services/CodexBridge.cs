using System.Diagnostics;
using System.Net.Http.Headers;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;
using ChuckieHelper.WebApi.Services.RemoteControl;

namespace ChuckieHelper.WebApi.Services;

public sealed class CodexBridge(IHttpClientFactory clients, IConfiguration configuration)
{
    private readonly SemaphoreSlim startup = new(1, 1);
    private readonly string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ChuckieHelper", "codex-bridge");
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
            return status.RootElement.TryGetProperty("implementation", out var implementation) && implementation.GetString() == "dotnet-v2";
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return false; }
        catch (HttpRequestException) { return false; }
        catch (JsonException) { return false; }
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
            var profile = OperatingSystem.IsWindows()
                ? Registry.GetValue($@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\{sid}", "ProfileImagePath", null)?.ToString()
                : Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            profile ??= Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            var accountStore = Path.Combine(profile, ".codex-switch");
            Directory.CreateDirectory(accountStore);
            if (OperatingSystem.IsWindows()) {
                var accountAcl = new DirectorySecurity();
                accountAcl.SetAccessRuleProtection(true, false);
                foreach (var account in new[] { sid, "S-1-5-18" }.Distinct()) accountAcl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(account), FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(accountStore).SetAccessControl(accountAcl);
            }
            var home = configuration["Codex:Home"] ?? Path.Combine(profile, ".codex");
            var executable = configuration["Codex:Executable"];
            if (string.IsNullOrWhiteSpace(executable)) {
                var bundled = Path.Combine(profile, "AppData", "Local", "OpenAI", "Codex", "bin");
                executable = Directory.Exists(bundled) ? Directory.EnumerateFiles(bundled, "codex.exe", SearchOption.AllDirectories).OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault() : null;
            }
            if (executable == null || !File.Exists(executable)) throw new InvalidOperationException("未找到本机 Codex，请安装 Codex 或配置 Codex:Executable");
            var dll = InteractiveProcessLauncher.GetApplicationDllPath();
            token ??= Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            await File.WriteAllTextAsync(configPath, JsonSerializer.Serialize(new { token, executable, home, account_store = accountStore,
                working_directory = configuration["Codex:WorkingDirectory"] ?? Path.Combine(profile, "Documents", "Codex", "Mobile"),
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
