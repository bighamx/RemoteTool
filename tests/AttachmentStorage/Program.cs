using RemoteTool.WebApi.Services;
using Microsoft.AspNetCore.Http;
using System.Text.Json;
using System.Text.Json.Nodes;

var fixture = Path.Combine(Path.GetTempPath(), "chuckie-attachment-check-" + Guid.NewGuid().ToString("N"));
var count = 0;
void Check(bool valid, string name) { if (!valid) throw new Exception(name); count++; }
JsonElement Body(string session, string[] ids = null) => JsonSerializer.SerializeToElement(new { session_id = session, input = "测试", attachment_ids = ids ?? [] });
try {
    JsonNode ToolFixture() => new JsonObject { ["data"] = new JsonArray(
        new JsonObject { ["id"] = 1, ["role"] = "user", ["content"] = "first", ["timestamp"] = 1 },
        new JsonObject { ["id"] = 2, ["role"] = "assistant", ["content"] = "", ["tool_calls"] = new JsonArray(new JsonObject { ["id"] = "call-a" }) },
        new JsonObject { ["id"] = 3, ["role"] = "tool", ["tool_call_id"] = "call-a", ["content"] = "large result" },
        new JsonObject { ["id"] = 4, ["role"] = "assistant", ["content"] = "done", ["timestamp"] = 2 },
        new JsonObject { ["id"] = 5, ["role"] = "user", ["content"] = "second", ["timestamp"] = 3 },
        new JsonObject { ["id"] = 6, ["role"] = "assistant", ["content"] = "", ["tool_calls"] = new JsonArray(new JsonObject { ["id"] = "call-b" }) },
        new JsonObject { ["id"] = 7, ["role"] = "tool", ["tool_call_id"] = "call-b", ["content"] = "live result", ["timestamp"] = 4 }) };
    var finished = CompletedToolHistory.Reduce(ToolFixture(), false)["data"].AsArray();
    Check(finished.All(row => row["role"].ToString() != "tool" && row["tool_calls"] == null), "completed history sends no tool details");
    Check(finished.Count(row => row["type"]?.ToString() == "toolSummary") == 2 && finished.Where(row => row["type"]?.ToString() == "toolSummary").All(row => row["tool_count"].GetValue<int>() == 1), "completed calls counted once despite matching results");
    var activeTools = CompletedToolHistory.Reduce(ToolFixture(), true, 3000)["data"].AsArray();
    Check(activeTools.Any(row => row["id"]?.ToString() == "7" && row["content"]?.ToString() == "live result"), "active turn keeps tool results");
    Check(activeTools.Any(row => row["id"]?.ToString() == "6" && row["tool_calls"] != null), "active turn keeps tool arguments");
    Check(activeTools.Count(row => row["type"]?.ToString() == "toolSummary") == 1, "only completed turn gets tool summary");
    var raw = new JsonArray();
    for (var index = 1; index <= 120; index++) {
        raw.Add(new JsonObject { ["id"] = index * 10, ["role"] = "user", ["content"] = "message " + index });
        for (var tool = 1; tool <= 4; tool++) raw.Add(new JsonObject { ["id"] = index * 10 + tool, ["role"] = "tool", ["content"] = "hidden" });
    }
    JsonNode Window(string from = null, string older = null) => AgentHistoryWindow.Select(new JsonObject { ["data"] = raw.DeepClone() }, 100, from, older);
    var recent = Window();
    Check(recent["data"].AsArray().Count(AgentHistoryWindow.Visible) == 100, "100 visible messages exclude tool rows");
    Check(recent["oldest_id"].ToString() == "210" && recent["has_more"].GetValue<bool>(), "stable native cursor points to first included message");
    var expanded = Window(older: "210");
    Check(expanded["data"].AsArray().Count(AgentHistoryWindow.Visible) == 120 && !expanded["has_more"].GetValue<bool>(), "older page expands a canonical window to the start");
    Check(Window(from: "10")["data"].AsArray().Count == raw.Count, "refresh retains the expanded canonical range");
    Check(Window(from: "removed")["oldest_id"].ToString() == "210", "removed rewind anchor falls back to current history");
    Check(expanded["data"].AsArray().Select(row => row["id"].ToString()).SequenceEqual(raw.Select(row => row["id"].ToString())), "native order remains unchanged");
    foreach (var agent in new[] { "codex", "hermes" }) {
        var root = Path.Combine(fixture, agent);
        var store = new HermesAttachments(agent, root);
        var session = "test-session";
        var folder = store.Folder(session);
        Check(!Directory.Exists(root), agent + " path calculation is read-only");
        Check(store.List(session).Length == 0 && !Directory.Exists(root), agent + " list absent session returns empty without creating it");
        var history = store.AddMessageAttachments(session, "{\"data\":[{\"id\":1,\"content\":\"test\"}]}");
        Check(history["data"].AsArray().Count == 1 && !Directory.Exists(root), agent + " history creates no directories");
        try { store.Resolve(session, "missing"); throw new Exception("missing attachment accepted"); }
        catch (FileNotFoundException) { Check(!Directory.Exists(root), agent + " missing file lookup creates no directories"); }
        store.Bind(session, "1", []);
        Check(!Directory.Exists(root), agent + " empty binding creates no metadata directories");
        var key = "request-key-test-0001";
        var codex = store.PrepareCodexRun(Body(session), key);
        var hermes = store.PrepareRun(Body(session), key);
        store.PrepareHermesSteer(Body(session), session, key);
        Check(codex.GetProperty("outbox").GetString() == Path.Combine(folder, "outbox", key), agent + " stable output path retained");
        Check(!Directory.Exists(root), agent + " text-only run and steer create no directories");
        Check(hermes.GetProperty("instructions").GetString().Contains("实际写入文件时"), agent + " writer instructed to create output lazily");

        using var input = new MemoryStream(System.Text.Encoding.UTF8.GetBytes("uploaded file"));
        var upload = new FormFile(input, 0, input.Length, "file", "hello.txt");
        var meta = JsonSerializer.SerializeToNode(await store.Upload(session, upload, default));
        var id = meta["id"].GetValue<string>();
        Check(Directory.Exists(folder) && !Directory.Exists(Path.Combine(folder, "outbox")), agent + " real upload creates session only");
        Check(File.ReadAllText(store.Resolve(session, id)) == "uploaded file", agent + " upload content resolves unchanged");
        var beforeUrl = meta["url"].GetValue<string>();
        store.Bind(session, "1", [id]);
        Check(File.Exists(Path.Combine(folder, ".chuckie-message-attachments.json")), agent + " real attachment binding persists");
        Check(store.List(session).Length == 1, agent + " internal binding file not listed as attachment");
        var forkMap = new JsonObject { ["1"] = "501" };
        store.Inherit(session, "fork", forkMap);
        var forkAttachment = store.AddMessageAttachments("fork", "{\"data\":[{\"id\":501,\"role\":\"user\",\"content\":\"file\"}]}")["data"][0]["attachments"][0];
        var inheritedId = forkAttachment["id"].GetValue<string>();
        Check(forkAttachment["url"].GetValue<string>() == beforeUrl, agent + " fork preserves source preview link");
        Check(store.Resolve("fork", inheritedId) == store.Resolve(session, id), agent + " fork reuses original file without copying it");
        Check(store.List("fork").Length == 1, agent + " fork lists inherited attachments");
        store.Bind("fork", "502", [inheritedId]);
        Check(store.AddMessageAttachments("fork", "{\"data\":[{\"id\":502}]}")["data"][0]["attachments"][0]["url"].GetValue<string>() == beforeUrl,
            agent + " edited fork can resend the inherited attachment");
        store.Inherit("fork", "fork-again", new JsonObject { ["501"] = "601" });
        Check(store.List("fork-again").Length == 1 && store.Resolve("fork-again", inheritedId) == store.Resolve(session, id), agent + " nested fork retains original file reference");
        store.Inherit(session, "empty-fork", new JsonObject { ["999"] = "999" });
        Check(!Directory.Exists(store.Folder("empty-fork")), agent + " attachment-free fork creates no file directory");
        var attached = store.AddMessageAttachments(session, "{\"data\":[{\"id\":1}]}");
        Check(attached["data"][0]["attachments"][0]["url"].GetValue<string>() == beforeUrl, agent + " attachment id and URL stable after history binding");
        if (agent == "codex") {
            var uploadedPath = store.Resolve(session, id);
            string NativeHistory(string sid, string role, string text) => store.AddMessageAttachments(sid,
                new JsonObject { ["data"] = new JsonArray(new JsonObject { ["id"] = 2, ["role"] = role, ["content"] = text }) }.ToJsonString()).ToJsonString();
            var nativeText = "请查看附件\n\n附件文件：\n\"" + uploadedPath + "\"";
            var recovered = JsonNode.Parse(NativeHistory(session, "user", nativeText));
            Check(recovered["data"][0]["attachments"][0]["url"].GetValue<string>() == beforeUrl,
                "native queued history recovers attachments without a message binding");
            Check(JsonNode.Parse(NativeHistory(session, "user", nativeText.Replace("\n", "\r\n")))["data"][0]["attachments"][0]["id"].GetValue<string>() == id,
                "native history recovery preserves attachment IDs across CRLF formatting");
            Check(JsonNode.Parse(NativeHistory("other-session", "user", nativeText))["data"][0]["attachments"] == null,
                "quoted paths from another session never become downloadable attachments");
            Check(JsonNode.Parse(NativeHistory(session, "assistant", nativeText))["data"][0]["attachments"] == null,
                "assistant quotes are not treated as uploaded user attachments");
            Check(JsonNode.Parse(NativeHistory(session, "user", "quoted path: \"" + uploadedPath + "\""))["data"][0]["attachments"] == null,
                "ordinary quoted paths do not create attachment associations");
            store.Bind(session, "2", []);
            Check(JsonNode.Parse(NativeHistory(session, "user", nativeText))["data"][0]["attachments"].AsArray().Count == 0,
                "an explicit empty binding remains authoritative over native inference");
        }
        store.PrepareRun(Body(session, [id]), key);
        store.PrepareCodexRun(Body(session, [id]), key);
        store.PrepareHermesSteer(Body(session, [id]), session, key);
        Check(!Directory.Exists(Path.Combine(folder, "outbox")), agent + " using uploads still creates no speculative output folders");

        var output = Path.Combine(folder, "outbox", key, "generated.txt");
        Directory.CreateDirectory(Path.GetDirectoryName(output)); File.WriteAllText(output, "generated content");
        var outputMeta = JsonSerializer.SerializeToNode(store.Metadata(session, output));
        Check(outputMeta["outgoing"].GetValue<bool>() && outputMeta["messageKey"].GetValue<string>() == key, agent + " lazily generated output metadata retained");
        Check(store.List(session).Length == 2 && store.Resolve(session, outputMeta["id"].GetValue<string>()) == output, agent + " generated files discoverable and downloadable");
        using var failing = new FailingStream();
        try { await store.Upload("failed-upload", new FormFile(failing, 0, 10, "file", "fail.txt"), default); throw new Exception("failed upload accepted"); }
        catch (IOException) { Check(!Directory.Exists(store.Folder("failed-upload")), agent + " aborted upload leaves no file or empty session folder"); }
        try { store.PrepareCodexRun(Body("invalid-attachment", ["missing"]), key); throw new Exception("invalid attachment accepted"); }
        catch (FileNotFoundException) { Check(!Directory.Exists(store.Folder("invalid-attachment")), agent + " rejected run leaves no folders"); }
        var source = Path.Combine(fixture, "project-" + agent, "音效 one.mp3");
        Directory.CreateDirectory(Path.GetDirectoryName(source)); File.WriteAllText(source, "audio snapshot");
        JsonNode Media(string role, string text) => store.AddMessageAttachments("media-test", new JsonObject {
            ["data"] = new JsonArray(new JsonObject { ["id"] = 100, ["role"] = role, ["content"] = text }) }.ToJsonString())["data"][0];
        Check(Media("user", "MEDIA:" + source)["attachments"] == null, agent + " user examples never import files");
        Check(Media("assistant", "```text\nMEDIA:" + source + "\n```")["attachments"] == null, agent + " fenced examples never import files");
        Check(!Directory.Exists(store.Folder("media-test")), agent + " examples create no folders");
        var imported = Media("assistant", "已生成\nMEDIA:" + source)["attachments"][0];
        Check(imported["name"].ToString() == "音效 one.mp3" && imported["mediaPath"].ToString() == source.Replace('\\', '/'), agent + " project MEDIA becomes an exact-path attachment");
        Check(File.ReadAllText(store.Resolve("media-test", imported["id"].ToString())) == "audio snapshot", agent + " imported content resolves without exposing the original");
        Check(Media("assistant", "MEDIA:" + source)["attachments"][0]["id"].ToString() == imported["id"].ToString(), agent + " repeated refresh keeps stable attachment identity");
        Check(store.List("media-test").Length == 1, agent + " repeated refresh does not duplicate copied files");
        File.WriteAllText(source, "updated audio snapshot");
        Check(Media("assistant", "MEDIA:" + source)["attachments"][0]["id"].ToString() != imported["id"].ToString(), agent + " source changes produce a new immutable snapshot");
    }
} finally { if (Directory.Exists(fixture)) Directory.Delete(fixture, true); }
Console.WriteLine($"Attachment storage checks: {count} passed");

sealed class FailingStream : MemoryStream {
    public FailingStream() : base(new byte[10]) { }
    public override Task CopyToAsync(Stream destination, int bufferSize, CancellationToken cancellationToken) => throw new IOException("Upload interrupted");
    public override int Read(byte[] buffer, int offset, int count) => throw new IOException("Upload interrupted");
    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => throw new IOException("Upload interrupted");
}
