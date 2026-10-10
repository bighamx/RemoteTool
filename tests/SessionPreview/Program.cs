using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Codex;
using System.Text.Json.Nodes;
using SQLite;

var count = 0;
void Check(bool condition, string name) { if (!condition) throw new Exception(name); count++; }
const string environment = "<environment_context>\n<current_date>2026-10-11</current_date>\n</environment_context>";
const string page = "<external_codex_apps_open_page>{\"page_id\":null}</external_codex_apps_open_page>";
Check(LatestSessionPreview.Text(environment) == "", "environment-only record has no preview");
Check(LatestSessionPreview.Text(page) == "", "page-only record has no preview");
Check(LatestSessionPreview.Text(environment + "\n" + page + "\n真正的消息") == "真正的消息", "mixed record retains actual human message");
Check(LatestSessionPreview.Text("说明：" + environment).StartsWith("说明："), "ordinary explanatory text remains");
Check(LatestSessionPreview.Text("```xml\n" + environment + "\n```").StartsWith("```xml"), "code example remains");
Check(LatestSessionPreview.Text("<environment_context><current_date>partial") == "", "partial context cannot become a preview");
var fixture = Path.Combine(Path.GetTempPath(), "preview-check-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(fixture);
try {
    SQLitePCL.Batteries_V2.Init();
    var database = Path.Combine(fixture, "history.sqlite");
    string Item(string text) => new JsonObject { ["type"] = "userMessage", ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = text }) }.ToJsonString();
    using (var db = new SQLiteConnection(database)) {
        db.Execute("CREATE TABLE thread_items(thread_id TEXT,item_json TEXT,item_type TEXT,rollout_ordinal INTEGER,created_at_ms INTEGER)");
        foreach (var (ordinal, text) in new[] { (1, "真正的最后一条消息"), (2, environment), (3, page) })
            db.Execute("INSERT INTO thread_items VALUES(?,?,?,?,?)", "thread", Item(text), "userMessage", ordinal, ordinal);
    }
    Check(LatestSessionPreview.Read(database, new[] { "thread" }, true)["thread"] == "真正的最后一条消息", "database skips newer metadata records");
    var home = Path.Combine(fixture, "home"); Directory.CreateDirectory(Path.Combine(home, "sessions"));
    var rollout = Path.Combine(home, "sessions", "rollout-test-thread_alias.jsonl");
    string Record(string text) => new JsonObject { ["type"] = "response_item", ["payload"] = new JsonObject { ["role"] = "user", ["content"] = new JsonArray(new JsonObject { ["type"] = "input_text", ["text"] = text }) } }.ToJsonString();
    File.WriteAllLines(rollout, new[] { Record("真正的最后一条消息"), Record(environment), Record(page) });
    Check(CodexPreviewRollout.Read(home, "thread", "") == "真正的最后一条消息", "continuation rollout uses the same filtering");
} finally { Directory.Delete(fixture, true); }
Console.WriteLine($"Session preview checks: {count} passed");
