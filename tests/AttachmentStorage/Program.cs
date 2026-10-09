using RemoteTool.WebApi.Services;
using Microsoft.AspNetCore.Http;
using System.Text.Json;
using System.Text.Json.Nodes;

var fixture = Path.Combine(Path.GetTempPath(), "chuckie-attachment-check-" + Guid.NewGuid().ToString("N"));
var count = 0;
void Check(bool valid, string name) { if (!valid) throw new Exception(name); count++; }
JsonElement Body(string session, string[] ids = null) => JsonSerializer.SerializeToElement(new { session_id = session, input = "测试", attachment_ids = ids ?? [] });
try {
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
    }
} finally { if (Directory.Exists(fixture)) Directory.Delete(fixture, true); }
Console.WriteLine($"Attachment storage checks: {count} passed");

sealed class FailingStream : MemoryStream {
    public FailingStream() : base(new byte[10]) { }
    public override Task CopyToAsync(Stream destination, int bufferSize, CancellationToken cancellationToken) => throw new IOException("Upload interrupted");
    public override int Read(byte[] buffer, int offset, int count) => throw new IOException("Upload interrupted");
    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => throw new IOException("Upload interrupted");
}
