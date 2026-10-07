using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace ChuckieHelper.WebApi.Services.Codex;

internal sealed class CodexError(string message, int status = 400, string code = "", string delivery = "") : Exception(message) {
    public int Status { get; } = status;
    public string Code { get; } = code;
    public string Delivery { get; } = delivery.Length > 0 ? delivery : status >= 500 ? "unknown" : "rejected";
}

internal static class CodexJson
{
    public static JsonObject Obj(params (string Key, object Value)[] values) {
        var result = new JsonObject();
        foreach (var (key, value) in values) result[key] = value is JsonNode node ? node.DeepClone() : System.Text.Json.JsonSerializer.SerializeToNode(value);
        return result;
    }
    public static string S(this JsonNode node, string key, string fallback = "") => node?[key]?.ToString() ?? fallback;
    public static JsonArray A(this JsonNode node, string key) => node?[key] as JsonArray ?? new JsonArray();
    public static bool B(this JsonNode node, string key) => bool.TryParse(node.S(key), out var result) && result;
    public static long L(this JsonNode node, string key) => long.TryParse(node.S(key), out var result) ? result : 0;
    public static void Atomic(string path, JsonNode value) {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp-" + Guid.NewGuid().ToString("N");
        try {
            using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                var bytes = Encoding.UTF8.GetBytes(value.ToJsonString()); stream.Write(bytes); stream.Flush(true);
            }
            File.Move(temporary, path, true);
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public static JsonObject Read(string path) => File.Exists(path) ? JsonNode.Parse(File.ReadAllText(path))!.AsObject() : new JsonObject();
    public static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
}

internal sealed class CodexRpc : IAsyncDisposable
{
    private readonly Process process;
    private readonly ConcurrentDictionary<long, TaskCompletionSource<JsonObject>> requests = new();
    private readonly SemaphoreSlim writeLock = new(1, 1);
    private readonly Func<JsonObject, Task> notification;
    private readonly Channel<JsonObject> notifications = Channel.CreateUnbounded<JsonObject>(new UnboundedChannelOptions { SingleReader = true, SingleWriter = true });
    private long counter;
    private int disposed;
    private readonly int processId;
    public int ProcessId => processId;
    public bool Running { get { if (Volatile.Read(ref disposed) != 0) return false; try { return !process.HasExited; } catch (InvalidOperationException) { return false; } } }
    public CodexRpc(string executable, string home, IDictionary<string, string> environment, Func<JsonObject, Task> notification, params string[] overrides) {
        this.notification = notification;
        var info = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
        info.ArgumentList.Add("app-server"); info.ArgumentList.Add("--stdio");
        foreach (var value in overrides) { info.ArgumentList.Add("-c"); info.ArgumentList.Add(value); }
        info.Environment["CODEX_HOME"] = home;
        foreach (var entry in environment) info.Environment[entry.Key] = entry.Value;
        process = Process.Start(info) ?? throw new CodexError("无法启动本机 Codex", 503);
        processId = process.Id;
        _ = DispatchNotifications();
        _ = ReadAsync();
        // Drain diagnostics without logging prompts, keys or private tool output.
        _ = Task.Run(async () => { while (await process.StandardError.ReadLineAsync() != null) { } });
    }
    public async Task Initialize(CancellationToken ct = default) {
        await Call("initialize", CodexJson.Obj(("clientInfo", CodexJson.Obj(("name", "chuckie_helper_mobile"), ("title", "ChuckieHelper Mobile"), ("version", "1.0"))),
            ("capabilities", CodexJson.Obj(("experimentalApi", true)))), ct);
        await Write(CodexJson.Obj(("method", "initialized")), ct);
    }
    public async Task<JsonObject> Call(string method, JsonObject parameters, CancellationToken ct = default) {
        var id = Interlocked.Increment(ref counter);
        var completion = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        requests[id] = completion;
        try {
            await Write(CodexJson.Obj(("id", id), ("method", method), ("params", parameters)), ct);
            return await completion.Task.WaitAsync(TimeSpan.FromSeconds(60), ct);
        } catch (TimeoutException) { throw new CodexError("Codex 请求超时，操作不会自动重发", 504); }
        finally { requests.TryRemove(id, out _); }
    }
    public async Task Write(JsonObject message, CancellationToken ct = default) {
        await writeLock.WaitAsync(ct);
        try {
            if (!Running) throw new CodexError("Codex 连接已关闭", 503);
            await process.StandardInput.WriteLineAsync(message.ToJsonString().AsMemory(), ct);
            await process.StandardInput.FlushAsync(ct);
        } finally { writeLock.Release(); }
    }
    private async Task ReadAsync() {
        try {
            while (await process.StandardOutput.ReadLineAsync() is { } line) {
                if (JsonNode.Parse(line) is not JsonObject message) continue;
                if (!message.ContainsKey("method") && long.TryParse(message.S("id"), out var id) && requests.TryGetValue(id, out var completion)) {
                    if (message["error"] is { } error) completion.TrySetException(new CodexError(error.S("message", "Codex 请求失败"), 409));
                    else completion.TrySetResult(message["result"]?.DeepClone() as JsonObject ?? new JsonObject());
                } else if (message.ContainsKey("method")) notifications.Writer.TryWrite(message);
            }
        } catch (Exception) { }
        finally { notifications.Writer.TryComplete(); foreach (var pending in requests.Values) pending.TrySetException(new CodexError("Codex 进程退出，任务不会自动重发", 503)); }
    }
    private async Task DispatchNotifications() {
        // Preserve notification order while allowing handlers to await RPC responses.
        await foreach (var message in notifications.Reader.ReadAllAsync()) {
            try { await notification(message); }
            catch (Exception error) when (error is CodexError or IOException or InvalidOperationException or OperationCanceledException) { }
        }
    }
    public async ValueTask DisposeAsync() {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        if (!process.HasExited) { process.StandardInput.Close(); if (!process.WaitForExit(1000)) process.Kill(true); }
        await process.WaitForExitAsync(); process.Dispose();
    }
}
