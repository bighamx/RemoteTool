using System.Net;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging.Abstractions;

internal static class ScheduledMessageDispatcherChecks
{
    private static HttpResponseMessage Response(int status, object body) => new((HttpStatusCode)status) {
        Content = new StringContent(JsonSerializer.Serialize(body))
    };

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "remotetool-schedule-dispatch-" + Guid.NewGuid().ToString("N"));
        private readonly Type storeType;
        private readonly Type dispatcherType;
        private readonly object logger;
        public object Store { get; private set; }
        public Func<HttpMethod, string, JsonElement?, CancellationToken, string, Task<HttpResponseMessage>> Send;
        public int Writes;
        public readonly long Now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

        public Fixture(Assembly assembly)
        {
            storeType = assembly.GetType("RemoteTool.WebApi.Services.Scheduled.ScheduledMessageStore", true)!;
            dispatcherType = assembly.GetType("RemoteTool.WebApi.Services.Scheduled.ScheduledMessageDispatcher", true)!;
            logger = typeof(NullLogger<>).MakeGenericType(dispatcherType).GetField("Instance")!.GetValue(null)!;
            Restore();
        }
        public void Restore() => Store = storeType.GetConstructor(BindingFlags.Instance | BindingFlags.NonPublic, null,
            [typeof(string)], null)!.Invoke([Path.Combine(root, "schedules.json")]);
        public object Invoke(string method, params object[] arguments) => storeType.GetMethod(method)!.Invoke(Store, arguments)!;
        public string Add(long? at = null)
        {
            var row = Invoke("Add", "scheduled-session", "isolated schedule fixture", at ?? Now, "", "", Now);
            return JsonSerializer.SerializeToNode(row)!["Id"]!.ToString();
        }
        public JsonNode Row(string id) => JsonSerializer.SerializeToNode(Invoke("Find", id))!;
        public async Task Tick(CancellationToken ct = default)
        {
            Task<HttpResponseMessage> Forward(HttpMethod method, string path, JsonElement? body, CancellationToken token, string key)
            {
                if (method == HttpMethod.Post) Writes++;
                return Send(method, path, body, token, key);
            }
            using var dispatcher = (IDisposable)dispatcherType.GetConstructors(BindingFlags.Instance | BindingFlags.NonPublic).Single()
                .Invoke([Store, (Func<HttpMethod, string, JsonElement?, CancellationToken, string, Task<HttpResponseMessage>>)Forward, logger]);
            await (Task)dispatcherType.GetMethod("TickAsync", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(dispatcher, [ct])!;
        }
        public void Dispose() { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    public static async Task Run(Assembly assembly)
    {
        using (var f = new Fixture(assembly))
        {
            var id = f.Add();
            f.Send = (method, path, body, ct, key) => {
                if (method == HttpMethod.Get) return Task.FromResult(Response(200, new { archived = false }));
                if (path != "runs" || body?.GetProperty("session_id").GetString() != "scheduled-session" ||
                    body?.GetProperty("defer_if_busy").GetBoolean() != true || key != "sched-" + id)
                    throw new Exception("Schedules must use the cross-client send path with a stable key and idle guard");
                return Task.FromResult(Response(200, new { run_id = "run-accepted", delivery = "accepted" }));
            };
            await f.Tick();
            if (f.Writes != 1 || f.Row(id)["Status"]!.ToString() != "sent") throw new Exception("Accepted schedule was lost");
            await f.Tick();
            if (f.Writes != 1) throw new Exception("Accepted schedule was sent twice");
        }
        using (var f = new Fixture(assembly))
        {
            var id = f.Add();
            f.Send = (method, path, body, ct, key) => Task.FromResult(method == HttpMethod.Get
                ? Response(200, new { archived = false })
                : Response(409, new { code = "session_writer_busy", delivery = "rejected" }));
            await f.Tick();
            if (f.Row(id)["Status"]!.ToString() != "pending" || f.Row(id)["Attempts"]!.GetValue<int>() != 0 ||
                f.Row(id)["ScheduledAt"]!.GetValue<long>() != f.Now) throw new Exception("Busy schedules must retain their original window and retry budget");
        }
        using (var f = new Fixture(assembly))
        {
            var id = f.Add();
            f.Send = (method, path, body, ct, key) => {
                if (method == HttpMethod.Post) throw new IOException("lost response");
                return Task.FromResult(Response(200, new { archived = false }));
            };
            await f.Tick();
            if (f.Row(id)["Status"]!.ToString() != "delivery_unknown") throw new Exception("Lost send responses must remain uncertain");
            f.Restore();
            f.Send = (method, path, body, ct, key) => {
                if (method != HttpMethod.Get || !path.StartsWith("runs/lookup?key=sched-")) throw new Exception("Uncertain sends must only be read back");
                return Task.FromResult(Response(200, new { found = true, session_id = "scheduled-session", run_id = "retained", delivery = "accepted" }));
            };
            await f.Tick();
            if (f.Writes != 1 || f.Row(id)["Status"]!.ToString() != "sent") throw new Exception("Restart reconciliation must recover acceptance without another send");
        }
        using (var f = new Fixture(assembly))
        {
            var id = f.Add();
            using var stop = new CancellationTokenSource();
            f.Send = (method, path, body, ct, key) => {
                if (method == HttpMethod.Post) { stop.Cancel(); ct.ThrowIfCancellationRequested(); }
                return Task.FromResult(Response(200, new { archived = false }));
            };
            try { await f.Tick(stop.Token); throw new Exception("Cancellation must propagate"); } catch (OperationCanceledException) { }
            f.Restore();
            f.Send = (method, path, body, ct, key) => Task.FromResult(Response(200, new { found = false }));
            await f.Tick();
            await f.Tick();
            if (f.Writes != 1 || f.Row(id)["Status"]!.ToString() != "delivery_unknown") throw new Exception("Interrupted sends without a receipt must never replay");
        }
        using (var f = new Fixture(assembly))
        {
            var id = f.Add(f.Now - 1800);
            f.Invoke("Postpone", id, f.Now - 1, "waiting", false);
            f.Send = (_, _, _, _, _) => throw new Exception("Expired schedules must not reach transport");
            await Task.Delay(1100);
            await f.Tick();
            if (f.Row(id)["Status"]!.ToString() != "failed" || f.Row(id)["CompletedAt"] == null)
                throw new Exception("Retries must expire against the original schedule and retain completion time");
        }
        Console.WriteLine("PASS scheduled dispatcher: cross-client delivery, busy deferral, durable unknown/cancelled recovery, no duplicate sends and original expiry window");
    }
}
