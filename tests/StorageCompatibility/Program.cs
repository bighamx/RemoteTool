using Hangfire;
using Hangfire.States;
using Hangfire.Storage.SQLite;
using Hangfire.Common;

var root = Path.Combine(Path.GetTempPath(), "chuckie-storage-check-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
try {
    var path = Path.Combine(root, "fixture.db");
    GlobalConfiguration.Configuration.SetDataCompatibilityLevel(CompatibilityLevel.Version_170);
    var storage = new SQLiteStorage(path);
    var client = new BackgroundJobClient(storage);
    // No BackgroundJobServer is started. This job is only a serialization/storage fixture.
    var id = client.Create(Job.FromExpression(() => Console.WriteLine("storage fixture")), new EnqueuedState());
    if (storage.GetMonitoringApi().EnqueuedCount("default") != 1) throw new Exception("queue count");
    using (var connection = storage.GetConnection()) {
        var job = connection.GetJobData(id);
        if (job?.Job?.Method.Name != "WriteLine" || job.State != "Enqueued") throw new Exception("stored job data");
    }
    if (!client.ChangeState(id, new DeletedState())) throw new Exception("delete state");
    using var db = new SQLite.SQLiteConnection(path, SQLite.SQLiteOpenFlags.ReadOnly);
    var version = db.ExecuteScalar<string>("select sqlite_version()");
    if (Version.Parse(version) < new Version(3, 50, 2)) throw new Exception("unpatched native engine");
    Console.WriteLine($"Hangfire create/read/state update passed; native SQLite {version}");
} finally {
    SQLite.SQLiteAsyncConnection.ResetPool();
    try { Directory.Delete(root, true); }
    catch (IOException) { Console.WriteLine("Storage provider retains the temporary fixture handle until this process exits."); }
}
