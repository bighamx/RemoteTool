using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

/** Keep accepted turns observable while replacing the bridge that owns them. */
internal sealed class CodexHandoff : IDisposable
{
    private readonly JsonNode settings;
    private readonly string token;
    private readonly HttpClient client = new() { Timeout = Timeout.InfiniteTimeSpan };
    private readonly ConcurrentDictionary<string, string> pending = new();
    private readonly Action<string, JsonObject> completed;
    private readonly CancellationTokenSource stopping = new();
    public CodexHandoff(JsonNode settings, string token, Action<string, JsonObject> completed) {
        this.settings = settings; this.token = token; this.completed = completed;
        if (settings?["runs"] is JsonObject runs) foreach (var run in runs) pending[run.Key] = run.Value?.ToString() ?? "";
    }
    public bool Contains(string id) => pending.ContainsKey(id);
    public bool OwnsSession(string session) => pending.Values.Contains(session);
    private HttpRequestMessage Request(HttpMethod method, string path) {
        var request = new HttpRequestMessage(method, $"http://127.0.0.1:{settings.L("port")}/{path}");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return request;
    }
    public async Task<JsonObject> Read(string id, CancellationToken ct = default) {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(5));
        using var request = Request(HttpMethod.Get, "runs/" + id);
        using var response = await client.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token))!.AsObject();
    }
    public async Task<bool> SessionBusy(string session) {
        foreach (var entry in pending.Where(entry => entry.Value == session)) {
            var state = await Read(entry.Key);
            if (state.S("status") is "started" or "submitting") return true;
            Finish(entry.Key, state);
        }
        return false;
    }
    private void Finish(string id, JsonObject state) { if (pending.TryRemove(id, out _)) completed(id, state); }
    public async Task<bool> Forward(HttpContext context, string path) {
        var parts = path.Split('/');
        if (parts.Length < 2 || parts[0] != "runs" || !Contains(parts[1])) return false;
        using var request = Request(new HttpMethod(context.Request.Method), path);
        if (context.Request.ContentLength is > 0) {
            request.Content = new StreamContent(context.Request.Body);
            request.Content.Headers.ContentLength = context.Request.ContentLength;
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }
        foreach (var header in new[] { "Idempotency-Key", "Last-Event-ID" })
            if (context.Request.Headers.TryGetValue(header, out var value)) request.Headers.TryAddWithoutValidation(header, value.ToString());
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, context.RequestAborted);
        context.Response.StatusCode = (int)response.StatusCode;
        context.Response.ContentType = response.Content.Headers.ContentType?.ToString() ?? "application/json";
        context.Response.Headers.CacheControl = "no-store";
        if (parts.Length == 2 && response.IsSuccessStatusCode) {
            var state = JsonNode.Parse(await response.Content.ReadAsStringAsync(context.RequestAborted))!.AsObject();
            if (state.S("status") is not ("started" or "submitting")) Finish(parts[1], state);
            await context.Response.WriteAsJsonAsync(state, context.RequestAborted);
        } else {
            context.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpResponseBodyFeature>()?.DisableBuffering();
            if (context.Response.ContentType.StartsWith("text/event-stream")) {
                using var reader = new StreamReader(await response.Content.ReadAsStreamAsync(context.RequestAborted));
                while (await reader.ReadLineAsync(context.RequestAborted) is { } line) {
                    await context.Response.WriteAsync(line + "\n", context.RequestAborted);
                    await context.Response.Body.FlushAsync(context.RequestAborted);
                }
            } else await response.Content.CopyToAsync(context.Response.Body, context.RequestAborted);
        }
        return true;
    }
    public async Task Retire() {
        if (settings == null) return;
        try {
            while (!stopping.IsCancellationRequested && !pending.IsEmpty) {
                foreach (var entry in pending.ToArray()) {
                    try {
                        var state = await Read(entry.Key, stopping.Token);
                        if (state.S("status") is not ("started" or "submitting")) Finish(entry.Key, state);
                    } catch (Exception error) when (error is HttpRequestException or OperationCanceledException && !stopping.IsCancellationRequested) {
                        if (OldProcessAlive()) continue; // A transient timeout must never terminate a live owner.
                        var unknown = Obj(("run_id", entry.Key), ("session_id", entry.Value), ("status", "acceptance_unknown"),
                            ("error", "原连接已中断，请核对真实会话历史；不会自动重发"), ("error_code", "run_tracking_lost"));
                        Finish(entry.Key, unknown);
                    }
                }
                if (!pending.IsEmpty) await Task.Delay(1000, stopping.Token);
            }
            await Task.Delay(3000, stopping.Token); // Allow the final event/response to reach existing followers.
            StopExact((int)settings.L("pid"), settings.L("process_started_ticks"), "dotnet");
            StopExact((int)settings.L("cli_pid"), settings.L("cli_started_ticks"), "codex");
        } catch (OperationCanceledException) { }
    }
    private bool OldProcessAlive() {
        if (settings.L("pid") <= 0) return true;
        try {
            using var process = Process.GetProcessById((int)settings.L("pid"));
            return !process.HasExited && process.StartTime.ToUniversalTime().Ticks == settings.L("process_started_ticks");
        } catch (ArgumentException) { return false; }
        catch (InvalidOperationException) { return false; }
        catch (System.ComponentModel.Win32Exception) { return true; }
    }
    private static void StopExact(int id, long started, string name) {
        if (id <= 0 || started <= 0 || id == Environment.ProcessId) return;
        try {
            using var process = Process.GetProcessById(id);
            if (process.ProcessName.Equals(name, StringComparison.OrdinalIgnoreCase) && process.StartTime.ToUniversalTime().Ticks == started)
                process.Kill(entireProcessTree: false);
        } catch (Exception error) when (error is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception) { }
    }
    public void Dispose() { stopping.Cancel(); client.Dispose(); stopping.Dispose(); }
}
