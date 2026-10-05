using ChuckieHelper.WebApi.Services;
using SQLite;
using System.Text.Json.Nodes;
using System.Security.Cryptography;

var root = Path.Combine(Path.GetTempPath(), "chuckie-external-hermes-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
var path = Path.Combine(root, "state.db");
const double now = 1791207000;
var count = 0;
void Check(bool valid, string message) { if (!valid) throw new Exception(message); count++; }
JsonObject Read(string id, Func<string, double, bool> alive = null) => HermesSessionActivity.ReadDatabase(path, [id], true, now, alive ?? ((_, _) => true))[id];
SQLitePCL.Batteries_V2.Init();
using var db = new SQLiteConnection(path);
db.Execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,parent_session_id TEXT,source TEXT,end_reason TEXT,model_config TEXT,last_activity_description TEXT,started_at REAL)");
db.Execute("CREATE TABLE session_turn_leases(conversation_id TEXT PRIMARY KEY,holder TEXT,acquired_at REAL,expires_at REAL)");
db.Execute("CREATE TABLE messages(id INTEGER PRIMARY KEY,session_id TEXT,role TEXT,timestamp REAL,tool_call_id TEXT,tool_calls TEXT,content TEXT,finish_reason TEXT,active INTEGER DEFAULT 1)");
db.Execute("INSERT INTO sessions VALUES('cli',NULL,'cli',NULL,'{}','Waiting for model',?)", now-100);
Check(!Read("cli")["running"].GetValue<bool>(), "open CLI session without a turn lease is idle");
db.Execute("INSERT INTO session_turn_leases VALUES('cli','pid=123:turn=t',?,?)", now-10, now+100);
var active = Read("cli");
Check(active["running"].GetValue<bool>() && active["started_at"].GetValue<long>() == (long)((now-10)*1000), "lease proves task and gives accurate start");
Check(active["last_response_at"] == null, "no model response time is invented before first reply");
Check(!active.ToJsonString().Contains("pid=123"), "private lease holder is not exposed");
Check(!Read("cli", (_, _) => false)["running"].GetValue<bool>(), "dead or recycled process cannot keep a run active");
db.Execute("UPDATE session_turn_leases SET expires_at=? WHERE conversation_id='cli'", now-1);
Check(!Read("cli")["running"].GetValue<bool>(), "expired lease is idle even with a live process");
db.Execute("UPDATE session_turn_leases SET expires_at=? WHERE conversation_id='cli'", now+100);
db.Execute("INSERT INTO messages VALUES(1,'cli','assistant',?,NULL,?,NULL,'tool_calls',1)", now-100, "[{\"id\":\"old\",\"function\":{\"name\":\"old_tool\",\"arguments\":\"{}\"}}]");
db.Execute("INSERT INTO messages VALUES(2,'cli','assistant',?,NULL,?,NULL,'tool_calls',1)", now-9, "[{\"id\":\"a\",\"function\":{\"name\":\"terminal\",\"arguments\":\"{\\\"command\\\":\\\"echo hello\\\"}\"}},{\"id\":\"b\",\"function\":{\"name\":\"terminal\",\"arguments\":\"{}\"}}]");
db.Execute("INSERT INTO messages VALUES(3,'cli','tool',?,'a',NULL,'done',NULL,1)", now-8);
active = Read("cli");
var progress = active["progress"].AsArray();
Check(active["event_count"].GetValue<int>() == 2 && progress.Count == 2, "only current turn calls counted; simultaneous same-name tools retained");
Check(progress[0]["status"].ToString() == "completed" && progress[1]["status"].ToString() == "running", "call IDs pair completions without collapsing names");
Check(progress[0]["preview"].ToString() == "echo hello", "command preview excludes raw JSON wrapper");
Check(active["last_response_at"].GetValue<long>() == (long)((now-8)*1000), "tool result updates response clock");
var identity = active["activity_id"].ToString();
db.Execute("INSERT INTO messages VALUES(4,'cli','tool',?,'b',NULL,'{\"error\":\"test error\"}',NULL,1)", now-7);
Check(Read("cli")["progress"][1]["status"].ToString() == "failed", "tool failures remain failures");
db.Execute("UPDATE session_turn_leases SET acquired_at=? WHERE conversation_id='cli'", now-2);
active = Read("cli");
Check(active["activity_id"].ToString() != identity && active["event_count"].GetValue<int>() == 0, "new turn has a different identity and excludes older tools");
db.Execute("UPDATE session_turn_leases SET acquired_at=? WHERE conversation_id='cli'", now-10);
for (var i=0; i<40; i++) db.Execute("INSERT INTO messages VALUES(?, 'cli','assistant',?,NULL,?,NULL,'tool_calls',1)", 10+i, now-6+i*0.01, "[{\"id\":\"c"+i+"\",\"function\":{\"name\":\"test\",\"arguments\":\"{}\"}}]");
active = Read("cli");
Check(active["event_count"].GetValue<int>() == 42 && active["progress"].AsArray().Count == 30, "total count survives compact display limit");
db.Execute("UPDATE sessions SET end_reason='compression' WHERE id='cli'");
db.Execute("INSERT INTO sessions VALUES('tip','cli','cli',NULL,'{}','Running tool',?)", now-5);
db.Execute("INSERT INTO sessions VALUES('branch','cli','cli',NULL,'{\"_branched_from\":\"cli\"}','',?)", now-1);
Check(Read("tip")["running"].GetValue<bool>() && Read("cli")["running"].GetValue<bool>(), "compression root and tip share the same active turn");
Check(!Read("branch")["running"].GetValue<bool>(), "independent branch never borrows parent's lease");
db.Execute("DELETE FROM session_turn_leases");
Check(!Read("cli")["running"].GetValue<bool>() && Read("cli")["progress"].AsArray().Count == 0, "lease release removes execution panel despite old calls");
byte[] HashDatabase() {
    using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
    return SHA256.HashData(stream);
}
var before = HashDatabase();
Read("cli");
Check(before.SequenceEqual(HashDatabase()), "observation never writes the native database");
Check(Read("missing")["available"].GetValue<bool>() && !Read("missing")["running"].GetValue<bool>(), "missing session is not running and is not created");
var missing = Path.Combine(root, "missing.db");
Check(!HermesSessionActivity.ReadDatabase(missing, ["cli"], true)["cli"]["available"].GetValue<bool>() && !File.Exists(missing), "unavailable database is not created");
var realNow = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()/1000.0;
db.Execute("INSERT INTO session_turn_leases VALUES('cli',?,?,?)", $"pid={Environment.ProcessId}:turn=kernel-check", realNow, realNow+120);
Check(HermesSessionActivity.ReadDatabase(path, ["cli"], true, realNow)["cli"]["running"].GetValue<bool>(), "real kernel process liveness proves active lease");
db.Execute("UPDATE session_turn_leases SET acquired_at=?", new DateTimeOffset(System.Diagnostics.Process.GetCurrentProcess().StartTime.ToUniversalTime()).ToUnixTimeSeconds()-10);
Check(!HermesSessionActivity.ReadDatabase(path, ["cli"], true, realNow)["cli"]["running"].GetValue<bool>(), "real newer process cannot adopt an older PID lease");
db.Execute("UPDATE session_turn_leases SET holder='pid=2147483647:turn=gone',acquired_at=?", realNow);
Check(!HermesSessionActivity.ReadDatabase(path, ["cli"], true, realNow)["cli"]["running"].GetValue<bool>(), "missing kernel process is idle");
db.Execute("UPDATE session_turn_leases SET holder='unverifiable-owner'");
Check(!HermesSessionActivity.ReadDatabase(path, ["cli"], true, realNow)["cli"]["available"].GetValue<bool>(), "unverifiable owner is unknown rather than guessed idle");
if (args.Length == 2 && args[0] == "--probe") {
    var actual = HermesSessionActivity.ReadDatabase(args[1], ["20261004_222746_dc3963"], true);
    var snapshot = actual.Values.First();
    Console.WriteLine("Native CLI snapshot: " + new JsonObject {
        ["available"] = snapshot["available"]?.DeepClone(), ["running"] = snapshot["running"]?.DeepClone(),
        ["source"] = snapshot["source"]?.DeepClone(), ["event_count"] = snapshot["event_count"]?.DeepClone(),
        ["started_at"] = snapshot["started_at"]?.DeepClone(), ["last_response_at"] = snapshot["last_response_at"]?.DeepClone(),
    }.ToJsonString());
}
Console.WriteLine($"External Hermes activity checks: {count} passed; temporary fixture: {root}");
// sqlite-net's provider can retain handles until process exit; this is an isolated temp fixture.
