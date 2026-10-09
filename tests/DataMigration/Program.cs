using RemoteTool.WebApi.Services;
using System.Text.Json;
using System.Text.Json.Nodes;

// 产品改名 ChuckieHelper → RemoteTool：验证数据目录的一次性迁移（搬运 + 标记 + 幂等 + 路径重写）。
// 全部在临时 fixture 上跑，不碰真实 ProgramData / LocalAppData / Temp。
var root = Path.Combine(Path.GetTempPath(), "remotetool-migration-check-" + Guid.NewGuid().ToString("N"));
var common = Path.Combine(root, "common");
var local = Path.Combine(root, "local");
var temp = Path.Combine(root, "temp");
foreach (var baseDir in new[] { common, local, temp }) Directory.CreateDirectory(baseDir);
try
{
    Check(RemoteToolPaths.ProductFolder == "RemoteTool", "canonical folder is RemoteTool");
    Check(RemoteToolPaths.LegacyProductFolders.SequenceEqual(new[] { "ChuckieHelper" }), "legacy folder list");

    // --- fixture: 旧布局（三代目录名，含会被重写的路径与不该被碰的日志）---
    var legacy = Path.Combine(common, "ChuckieHelper");
    Directory.CreateDirectory(Path.Combine(legacy, "codex-bridge", "state-v10"));
    Directory.CreateDirectory(Path.Combine(legacy, "hermes-attachments", "20260916_183827_51cdeb61"));
    Directory.CreateDirectory(Path.Combine(legacy, "logs"));
    File.WriteAllText(Path.Combine(legacy, "run-registry.json"),
        JsonSerializer.Serialize(new Dictionary<string, object> {
            ["hermes:s1"] = new { run_id = "hcompact_1", path = Path.Combine(legacy, "codex-bridge", "x.json") }
        }));
    File.WriteAllText(Path.Combine(legacy, "codex-bridge", "connection.json"),
        JsonSerializer.Serialize(new { state_folder = Path.Combine(legacy, "codex-bridge", "state-v10"),
            attachments = Path.Combine(legacy, "codex-attachments").Replace('\\', '/'),
            unrelated = legacy + "-Other/file", note = "Keep /ChuckieHelper/ in user text" }));
    File.WriteAllText(Path.Combine(legacy, "codex-bridge", "state-v10", "runs.json"),
        "{\"r1\":{\"status\":\"completed\",\"note\":\"E:/GIT/RemoteTool/app/mobile 构建\"}}");
    File.WriteAllText(Path.Combine(legacy, "logs", "desktop-agent-startup.log"), "old log line");
    File.WriteAllText(Path.Combine(legacy, "hermes-attachments", "20260916_183827_51cdeb61", "payload.bin"), "attachment payload");
    Directory.CreateDirectory(Path.Combine(local, "ChuckieHelper"));
    File.WriteAllText(Path.Combine(local, "ChuckieHelper", "device-id"), "legacy-device-id");
    Directory.CreateDirectory(Path.Combine(temp, "ChuckieHelper"));
    File.WriteAllText(Path.Combine(temp, "ChuckieHelper", "desktop-agent-startup.log"), "temp log");

    RemoteToolDataMigration.Migrate(common, local, temp);

    var destination = Path.Combine(common, "RemoteTool");
    Check(!Directory.Exists(legacy), "legacy ProgramData root removed");
    Check(Directory.Exists(destination), "destination created");
    Check(File.Exists(Path.Combine(destination, "data-migration.done")), "marker written");
    Check(File.Exists(Path.Combine(destination, "codex-bridge", "connection.json")), "nested bridge file moved");
    Check(File.Exists(Path.Combine(destination, "codex-bridge", "state-v10", "runs.json")), "nested state journal moved");
    Check(File.Exists(Path.Combine(destination, "logs", "desktop-agent-startup.log")), "log file moved");
    Check(File.ReadAllText(Path.Combine(destination, "hermes-attachments", "20260916_183827_51cdeb61", "payload.bin")) == "attachment payload",
        "attachment payload intact");
    Check(!Directory.Exists(Path.Combine(local, "ChuckieHelper")) && File.Exists(Path.Combine(local, "RemoteTool", "device-id")),
        "LocalAppData device id relocated");
    Check(!Directory.Exists(Path.Combine(temp, "ChuckieHelper")) && File.Exists(Path.Combine(temp, "RemoteTool", "desktop-agent-startup.log")),
        "temp log relocated");

    // 重写：JSON 里旧绝对路径改成新路径（转义反斜杠），不相干的路径不动
    var registry = File.ReadAllText(Path.Combine(destination, "run-registry.json"));
    Check(JsonNode.Parse(registry)!["hermes:s1"]!["path"]!.ToString() == Path.Combine(destination, "codex-bridge", "x.json"),
        "run-registry path rewritten");
    var connection = File.ReadAllText(Path.Combine(destination, "codex-bridge", "connection.json"));
    var connectionJson = JsonNode.Parse(connection)!;
    Check(connectionJson["state_folder"]!.ToString() == Path.Combine(destination, "codex-bridge", "state-v10"),
        "connection.json paths rewritten");
    Check(connectionJson["attachments"]!.ToString() == Path.Combine(destination, "codex-attachments"), "forward-slash path rewritten");
    Check(connectionJson["unrelated"]!.ToString() == legacy + "-Other/file"
        && connectionJson["note"]!.ToString() == "Keep /ChuckieHelper/ in user text", "prefix neighbours and user text unchanged");
    var runs = File.ReadAllText(Path.Combine(destination, "codex-bridge", "state-v10", "runs.json"));
    Check(runs.Contains("E:/GIT/RemoteTool/app/mobile") && !runs.Contains("ChuckieHelper"), "unrelated path untouched, no false rewrite");
    Check(File.ReadAllText(Path.Combine(destination, "logs", "desktop-agent-startup.log")) == "old log line", "log content not rewritten");

    // 幂等：再跑一次不改变结果，也不接受新布局被搬走
    var markerBefore = File.GetLastWriteTimeUtc(Path.Combine(destination, "data-migration.done"));
    RemoteToolDataMigration.Migrate(common, local, temp);
    Check(File.Exists(Path.Combine(destination, "codex-bridge", "connection.json")), "second run leaves data in place");
    Check(File.GetLastWriteTimeUtc(Path.Combine(destination, "data-migration.done")) == markerBefore, "second run is a no-op");

    // 合并：两边都有同名文件时，以新目录已有内容为准，不覆盖不丢失
    Directory.CreateDirectory(Path.Combine(legacy, "codex-bridge"));
    File.WriteAllText(Path.Combine(legacy, "codex-bridge", "connection.json"), "should-not-overwrite");
    File.WriteAllText(Path.Combine(legacy, "codex-bridge", "extra.json"), "migrated-extra");
    File.Delete(Path.Combine(destination, "data-migration.done"));
    RemoteToolDataMigration.Migrate(common, local, temp);
    Check(File.ReadAllText(Path.Combine(destination, "codex-bridge", "connection.json")).Contains("RemoteTool"),
        "existing destination file wins (no overwrite)");
    Check(File.Exists(Path.Combine(destination, "codex-bridge", "extra.json")), "missing file merged in");

    Check(!File.Exists(Path.Combine(destination, "data-migration.done")), "conflict does not falsely complete migration");
    Check(File.ReadAllText(Path.Combine(legacy, "codex-bridge", "connection.json")) == "should-not-overwrite", "conflicting source retained");
    File.Delete(Path.Combine(legacy, "codex-bridge", "connection.json"));
    RemoteToolDataMigration.Migrate(common, local, temp);
    Check(File.Exists(Path.Combine(destination, "data-migration.done")), "retry completes after conflict resolved");

    // A different desktop user must migrate even if ProgramData already has its marker.
    var otherLocal = Path.Combine(root, "other-user");
    Directory.CreateDirectory(Path.Combine(otherLocal, "ChuckieHelper"));
    File.WriteAllText(Path.Combine(otherLocal, "ChuckieHelper", "device-id"), "other-device-id");
    RemoteToolDataMigration.Migrate(common, otherLocal, temp);
    Check(File.ReadAllText(Path.Combine(otherLocal, "RemoteTool", "device-id")) == "other-device-id", "per-user migration independent of machine marker");

    // A failed move must retry, including when a pre-existing marker is present.
    Directory.CreateDirectory(legacy);
    var busyFile = Path.Combine(legacy, "busy.txt");
    File.WriteAllText(busyFile, "held payload");
    using (var held = new FileStream(busyFile, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
    {
        RemoteToolDataMigration.Migrate(common, local, temp);
        Check(File.Exists(busyFile) && !File.Exists(Path.Combine(destination, "data-migration.done")), "locked source remains pending");
    }
    RemoteToolDataMigration.Migrate(common, local, temp);
    Check(File.ReadAllText(Path.Combine(destination, "busy.txt")) == "held payload", "locked file migrates after release");
    Check(File.Exists(Path.Combine(destination, "data-migration.done")), "retry records completion");

    Console.WriteLine("PASS: RemoteTool data-directory migration (move, rewrite, idempotent, merge)");
}
finally
{
    try { Directory.Delete(root, true); } catch (IOException) { }
}

static void Check(bool condition, string what)
{
    if (!condition) throw new Exception("FAILED: " + what);
    Console.WriteLine("  ok: " + what);
}
