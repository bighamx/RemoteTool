using Hangfire;
using Hangfire.States;
using Hangfire.Storage.SQLite;
using Hangfire.Common;
using RemoteTool.WebApi.Controllers;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using Hangfire.Console;
using Hangfire.Server;
using Hangfire.Storage;
using RemoteTool.WebApi.Services;

GlobalConfiguration.Configuration.UseTypeResolver(HangfireTypeResolver.Resolve);
foreach (var priorAssembly in new[] { "ChuckieHelper.WebApi", "WebApplication1" })
{
    var payload = JsonSerializer.Serialize(new {
        Type = $"ChuckieHelper.WebApi.Jobs.LegacyJobFixture, {priorAssembly}",
        Method = "Run", ParameterTypes = "[]", Arguments = "[]"
    });
    var legacyJob = InvocationData.DeserializePayload(payload).DeserializeJob();
    if (legacyJob.Type != typeof(ChuckieHelper.WebApi.Jobs.LegacyJobFixture) || legacyJob.Method.Name != "Run")
        throw new Exception("Legacy Hangfire task failed to resolve to the current assembly");
}
Console.WriteLine("Legacy Hangfire assembly names deserialize to the current task (not executed)");

var root = Path.Combine(Path.GetTempPath(), "chuckie-storage-check-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
try {
    var path = Path.Combine(root, "fixture.db");
    GlobalConfiguration.Configuration.SetDataCompatibilityLevel(CompatibilityLevel.Version_170);
    var storage = new SQLiteStorage(path);
    JobStorage.Current = storage;
    var client = new BackgroundJobClient(storage);
    // No BackgroundJobServer is started. This job is only a serialization/storage fixture.
    var id = client.Create(Job.FromExpression(() => Console.WriteLine("storage fixture")), new EnqueuedState());
    if (storage.GetMonitoringApi().EnqueuedCount("default") != 1) throw new Exception("queue count");
    var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
    // Reproduce the production fault with the actual SQLite monitoring DTO, not a mock.
    try {
        JsonSerializer.Serialize(storage.GetMonitoringApi().Queues(), options);
        throw new Exception("Expected the raw queued-job reflection object to reject JSON serialization");
    } catch (NotSupportedException error) when (error.Message.Contains("System.Type")) { }
    var controller = new NativeJobsController();
    string Json(IActionResult action) => JsonSerializer.Serialize(((OkObjectResult)action).Value, options);
    using (var overview = JsonDocument.Parse(Json(controller.Overview()))) {
        var queue = overview.RootElement.GetProperty("queues")[0];
        if (queue.GetProperty("name").GetString() != "default" || queue.GetProperty("length").GetInt64() != 1)
            throw new Exception("queue summary/count");
        var first = queue.GetProperty("firstJobs")[0];
        if (first.GetProperty("id").GetString() != id || first.GetProperty("type").GetString() != typeof(Console).FullName ||
            first.GetProperty("method").GetString() != "WriteLine") throw new Exception("JSON-safe queued job summary");
    }
    using (var detail = JsonDocument.Parse(Json(controller.Detail(id)))) {
        if (detail.RootElement.GetProperty("method").GetString() != "WriteLine") throw new Exception("job detail JSON");
    }
    using (var list = JsonDocument.Parse(Json(controller.Jobs("enqueued")))) {
        if (list.RootElement.GetProperty("data").GetArrayLength() != 1) throw new Exception("enqueued job list JSON");
    }
    using (var connection = storage.GetConnection()) {
        var job = connection.GetJobData(id);
        if (job?.Job?.Method.Name != "WriteLine" || job.State != "Enqueued") throw new Exception("stored job data");
    }
    if (!client.ChangeState(id, new DeletedState())) throw new Exception("delete state");
    Json(controller.Overview()); // Empty queue remains serializable too.
    var manager = new RecurringJobManager(storage);
    manager.AddOrUpdate("trigger-fixture", Job.FromExpression(() => Console.WriteLine("trigger fixture")), "0 0 1 1 *", new RecurringJobOptions { TimeZone = TimeZoneInfo.Utc });
    var triggered = manager.TriggerJob("trigger-fixture");
    if (string.IsNullOrWhiteSpace(triggered)) throw new Exception("recurring trigger did not enqueue");
    using (var overview = JsonDocument.Parse(Json(controller.Overview()))) {
        if (overview.RootElement.GetProperty("recurring").GetArrayLength() != 1 ||
            !overview.RootElement.GetProperty("queues")[0].GetProperty("firstJobs").EnumerateArray().Any(job=>job.GetProperty("id").GetString()==triggered))
            throw new Exception("refresh after recurring trigger: " + overview.RootElement.ToString());
    }
    manager.RemoveIfExists("trigger-fixture");
    client.ChangeState(triggered, new DeletedState());
    GlobalConfiguration.Configuration.UseConsole();
    var logJob = client.Create(Job.FromExpression<ConsoleFixture>(job=>job.Run(null)),new EnqueuedState("console-fixture"));
    using (var server = new BackgroundJobServer(new BackgroundJobServerOptions { WorkerCount=1, Queues=new[]{"console-fixture"} },storage)) {
        try {
            if(!ConsoleFixture.Ready.Wait(TimeSpan.FromSeconds(15)))throw new Exception("console fixture did not start");
            using var current=JsonDocument.Parse(Json(controller.Detail(logJob)));
            if(current.RootElement.GetProperty("state").GetString()!="Processing")throw new Exception("running detail state");
            var attempt=current.RootElement.GetProperty("attempts")[0].GetProperty("id").GetString();
            using var page1=JsonDocument.Parse(Json(controller.ConsoleLog(logJob,attempt,0,2)));
            var data1=page1.RootElement.GetProperty("data");
            if(data1.GetArrayLength()!=2 || !page1.RootElement.GetProperty("hasMore").GetBoolean() ||
                data1[0].GetProperty("text").GetString()!="你好，运行时日志" || data1[1].GetProperty("text").GetString()!=ConsoleFixture.LongText)
                throw new Exception("console pagination/Unicode/reference decoding: "+page1.RootElement);
            using var page2=JsonDocument.Parse(Json(controller.ConsoleLog(logJob,attempt,page1.RootElement.GetProperty("nextOffset").GetInt32(),2)));
            if(page2.RootElement.GetProperty("data")[1].GetProperty("progress").GetDouble()!=85)
                throw new Exception("console progress updates");
            if(controller.ConsoleLog(logJob,"unknown") is not NotFoundObjectResult)throw new Exception("attempt boundary");
            using var reset=JsonDocument.Parse(Json(controller.ConsoleLog(logJob,attempt,int.MaxValue,200)));
            if(!reset.RootElement.GetProperty("reset").GetBoolean())throw new Exception("expired/stale cursor reset");
            ConsoleFixture.Release.Set();
            var deadline=DateTime.UtcNow.AddSeconds(10);
            while(DateTime.UtcNow<deadline) { using var check=storage.GetConnection();if(check.GetJobData(logJob).State=="Succeeded")break;Thread.Sleep(50); }
            using var ended=JsonDocument.Parse(Json(controller.ConsoleLog(logJob,attempt)));
            if(ended.RootElement.GetProperty("total").GetInt32()<4)throw new Exception("completed logs retention");
            Console.WriteLine("Live console fixture: running logs, Unicode/long references, paged cursor, progress, invalid attempt, reset and completed history passed");
        } finally { ConsoleFixture.Release.Set(); }
    }
    using var db = new SQLite.SQLiteConnection(path, SQLite.SQLiteOpenFlags.ReadOnly);
    var version = db.ExecuteScalar<string>("select sqlite_version()");
    if (Version.Parse(version) < new Version(3, 50, 2)) throw new Exception("unpatched native engine");
    Console.WriteLine($"Hangfire create/read/state update and 5 controller serialization checks passed; native SQLite {version}");
} finally {
    SQLite.SQLiteAsyncConnection.ResetPool();
    try { Directory.Delete(root, true); }
    catch (IOException) { Console.WriteLine("Storage provider retains the temporary fixture handle until this process exits."); }
}

public sealed class ConsoleFixture {
    public static readonly ManualResetEventSlim Ready=new(false),Release=new(false);
    public static readonly string LongText=new('测',400);
    public void Run(PerformContext context) {
        context.WriteLine("你好，运行时日志");
        context.WriteLine(ConsoleTextColor.Red,LongText);
        var bar=context.WriteProgressBar("测试进度",5);bar.SetValue(85);
        Ready.Set();
        if(!Release.Wait(TimeSpan.FromSeconds(25)))throw new TimeoutException("Fixture release timeout");
    }
}
