using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Diagnostics;
using System.Security.Principal;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services;


/// <summary>Server-only credential access. No client-supplied upstream URL or key.</summary>
public sealed class HermesBridge(IHttpClientFactory clients, IConfiguration configuration)
{
    public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, JsonElement? body, string? idempotency, CancellationToken ct, string? lastEvent = null)
    {
        var key = new HermesProfiles(configuration).ApiKey(HermesProfileScope.Name);
        if (string.IsNullOrWhiteSpace(key)) throw new HermesSetupRequired("Hermes 尚未接入：请启用本机 API Server，并配置 API_SERVER_KEY 密钥文件");
        var root = new Uri(configuration["Hermes:BaseUrl"] ?? "http://127.0.0.1:8642/");
        if (!root.IsLoopback || root.Scheme is not ("http" or "https")) throw new InvalidOperationException("Hermes 必须使用服务端本机地址");
        EnsureAttachmentAccess();
        var routedPath = HermesProfileScope.Name == "default" ? path : "p/" + HermesProfileScope.Name + "/" + path;
        using var request = new HttpRequestMessage(method, new Uri(root, routedPath));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (idempotency != null) request.Headers.Add("Idempotency-Key", idempotency);
        if (long.TryParse(lastEvent, out var sequence) && sequence >= 0) request.Headers.Add("Last-Event-ID", sequence.ToString());
        if (body != null) request.Content = new StringContent(body.Value.GetRawText(), Encoding.UTF8, "application/json");
        return await clients.CreateClient("hermes").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
    }

    private static void EnsureAttachmentAccess()
    {
        if (!OperatingSystem.IsWindows()) return;
        try {
            using var current = WindowsIdentity.GetCurrent();
            var userSid = current.User!.Value;
            if (InteractiveProcessLauncher.IsRunningInSession0) {
                try { userSid = InteractiveProcessLauncher.GetInteractiveUserSid(); }
                catch (NoInteractiveUserException) { /* A headless gateway can still use the service identity. */ }
            }
            // Keep existing ACLs, adding inheritable access for the native worker.
            Codex.CodexAttachmentAccess.Ensure(RemoteToolPaths.Attachments("hermes"), userSid);
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException) {
            Trace.TraceWarning("Hermes attachment permissions could not be refreshed: {0}", error.GetType().Name);
        }
    }
}
