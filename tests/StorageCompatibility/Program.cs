using Hangfire;
using Hangfire.States;
using Hangfire.Storage.SQLite;
using Hangfire.Common;
using ChuckieHelper.WebApi.Controllers;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;

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
    using var db = new SQLite.SQLiteConnection(path, SQLite.SQLiteOpenFlags.ReadOnly);
    var version = db.ExecuteScalar<string>("select sqlite_version()");
    if (Version.Parse(version) < new Version(3, 50, 2)) throw new Exception("unpatched native engine");
    Console.WriteLine($"Hangfire create/read/state update and 5 controller serialization checks passed; native SQLite {version}");
} finally {
    SQLite.SQLiteAsyncConnection.ResetPool();
    try { Directory.Delete(root, true); }
    catch (IOException) { Console.WriteLine("Storage provider retains the temporary fixture handle until this process exits."); }
}
