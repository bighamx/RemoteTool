using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services;

/// <summary>Server-only credential access. No client-supplied upstream URL or key.</summary>
public sealed class HermesBridge(IHttpClientFactory clients, IConfiguration configuration)
{
    public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, JsonElement? body, string? idempotency, CancellationToken ct, string? lastEvent = null)
    {
        var keyPath = configuration["Hermes:KeyFile"] ?? Environment.GetEnvironmentVariable("HERMES_API_KEY_FILE")
            ?? RemoteToolPaths.HermesKeyFile;
        string? key = null;
        if (File.Exists(keyPath))
            foreach (var line in File.ReadLines(keyPath))
            {
                var parts = line.Split('=', 2);
                if (parts.Length == 2 && parts[0].Trim() == "API_SERVER_KEY") { key = parts[1].Trim().Trim('"', '\''); break; }
            }
        if (string.IsNullOrWhiteSpace(key)) throw new InvalidOperationException("服务端尚未配置 Hermes API_SERVER_KEY 文件");
        var root = new Uri(configuration["Hermes:BaseUrl"] ?? "http://127.0.0.1:8642/");
        if (!root.IsLoopback || root.Scheme is not ("http" or "https")) throw new InvalidOperationException("Hermes 必须使用服务端本机地址");
        using var request = new HttpRequestMessage(method, new Uri(root, path));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (idempotency != null) request.Headers.Add("Idempotency-Key", idempotency);
        if (long.TryParse(lastEvent, out var sequence) && sequence >= 0) request.Headers.Add("Last-Event-ID", sequence.ToString());
        if (body != null) request.Content = new StringContent(body.Value.GetRawText(), Encoding.UTF8, "application/json");
        return await clients.CreateClient("hermes").SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
    }
}
