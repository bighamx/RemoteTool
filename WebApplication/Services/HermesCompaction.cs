using System.Text.Json;
using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services;

public sealed class HermesCompaction
{
    private readonly HermesManagement management;
    private readonly object gate = new();
    private readonly string folder;
    private readonly Dictionary<string, JsonObject> runs = new();
    private readonly Dictionary<string, CancellationTokenSource> cancellations = new();
    public HermesCompaction(HermesManagement management) {
        this.management = management;
        folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ChuckieHelper", "hermes-compactions");
        Directory.CreateDirectory(folder);
        foreach (var path in Directory.EnumerateFiles(folder, "hcompact_*.json")) {
            try {
                var state = Read(path);
                if (state.S("status") == "started") { state["status"] = "acceptance_unknown"; state["error"] = "服务重启，请核对上下文状态；不会自动重新压缩"; Atomic(path, state); }
                runs[Path.GetFileNameWithoutExtension(path)] = state;
            } catch (JsonException) { }
        }
    }
    public JsonObject Start(string session, string key) {
        lock (gate) {
            var existing = runs.FirstOrDefault(row => row.Value.S("key") == key);
            if (existing.Value != null) {
                if (existing.Value.S("session_id") != session) throw new InvalidOperationException("相同请求标识对应了其他会话");
                return Obj(("run_id", existing.Key), ("replayed", true));
            }
            if (runs.Values.Any(row => row.S("status") == "started")) throw new InvalidOperationException("已有上下文正在压缩，请等待完成");
            var id = "hcompact_" + Guid.NewGuid().ToString("N");
            runs[id] = Obj(("run_id", id), ("session_id", session), ("key", key), ("kind", "compact"), ("status", "started"), ("output", ""));
            Atomic(Path.Combine(folder, id + ".json"), runs[id]);
            var cancellation = cancellations[id] = new();
            _ = Execute(id, session, cancellation);
            return Obj(("run_id", id), ("status", "started"));
        }
    }
    private async Task Execute(string id, string session, CancellationTokenSource cancellation) {
        try {
            var result = await management.Invoke("compress_session", JsonSerializer.SerializeToElement(new { session_id = session }), cancellation.Token);
            lock (gate) {
                var success = result.TryGetProperty("success", out var ok) && ok.GetBoolean();
                runs[id]["status"] = success ? "completed" : "failed";
                runs[id]["output"] = success ? result.GetProperty("message").GetString() : "";
                if (!success) runs[id]["error"] = result.GetProperty("message").GetString();
                runs[id]["compression"] = JsonNode.Parse(result.GetRawText());
            }
        } catch (OperationCanceledException) {
            lock (gate) { runs[id]["status"] = "acceptance_unknown"; runs[id]["error"] = "压缩已停止或超时，请核对上下文状态；不会自动重试"; }
        } catch (Exception error) when (error is InvalidOperationException or IOException or JsonException) {
            lock (gate) { runs[id]["status"] = "failed"; runs[id]["error"] = "Hermes 压缩未完成，请检查模型配置，原始历史已保留"; }
        } finally {
            lock (gate) { Atomic(Path.Combine(folder, id + ".json"), runs[id]); cancellations.Remove(id); }
            cancellation.Dispose();
        }
    }
    public JsonObject Status(string id) { lock (gate) return runs.TryGetValue(id, out var state) ? state.DeepClone().AsObject() : throw new KeyNotFoundException(); }
    public void Stop(string id) { lock (gate) { if (!runs.ContainsKey(id)) throw new KeyNotFoundException(); if (cancellations.TryGetValue(id, out var cancel)) cancel.Cancel(); } }
    public async Task Events(string id, HttpResponse response, CancellationToken ct) {
        response.ContentType = "text/event-stream"; response.Headers.CacheControl = "no-store";
        while (!ct.IsCancellationRequested) {
            var status = Status(id).S("status");
            await response.WriteAsync(status == "started" ? ": keepalive\n\n" : "data: " + Obj(("event", status == "completed" ? "run.completed" : "run.failed"), ("seq", 1), ("run_id", id)).ToJsonString() + "\n\n", ct);
            await response.Body.FlushAsync(ct);
            if (status != "started") return;
            await Task.Delay(1000, ct);
        }
    }
}
