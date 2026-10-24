using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services;

/// <summary>Server-only credential access. No client-supplied upstream URL or key.</summary>
public sealed class HermesBridge(IHttpClientFactory clients, IConfiguration configuration)
{
    public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, JsonElement? body, string? idempotency, CancellationToken ct, string? lastEvent = null)
    {
        var key = new HermesProfiles(configuration).ApiKey(HermesProfileScope.Name);
        if (string.IsNullOrWhiteSpace(key)) throw new InvalidOperationException("服务端尚未配置 Hermes API_SERVER_KEY 文件");
        var root = new Uri(configuration["Hermes:BaseUrl"] ?? "http://127.0.0.1:8642/");
        if (!root.IsLoopback || root.Scheme is not ("http" or "https")) throw new InvalidOperationException("Hermes 必须使用服务端本机地址");
        var routedPath = HermesProfileScope.Name == "default" ? path : "p/" + HermesProfileScope.Name + "/" + path;
        using var request = new HttpRequestMessage(method, new Uri(root, routedPath));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (idempotency != null) request.Headers.Add("Idempotency-Key", idempotency);
        if (long.TryParse(lastEvent, out var sequence) && sequence >= 0) request.Headers.Add("Last-Event-ID", sequence.ToString());
        if (body != null) request.Content = new StringContent(body.Value.GetRawText(), Encoding.UTF8, "application/json");
        return await clients.CreateClient("hermes").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
    }
}
