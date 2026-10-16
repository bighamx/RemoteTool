using System.Diagnostics;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services.Codex;

// Codex 桌面端（Store 包 OpenAI.Codex → ChatGPT.exe）把「会话属于哪个项目」存在自己的
// ~/.codex/.codex-global-state.json 里：
//   local-projects                          桌面端自己的项目实体（legacy UUID id + rootPaths）
//   thread-project-assignments              会话 → {projectKind, projectId(legacy)}
//   sidebar-project-thread-orders           每个项目下的会话顺序（侧栏就是按它渲染）
//   app-server-project-id-by-legacy-project-id-by-host   legacy id ↔ app-server project id
//   app-server-projects-migration-by-host    还没推给 app-server 的待迁移分配
// 侧栏分组只认这份本地状态；app-server/state_5.sqlite 的 threads.project_id 是另一份记录
// （手机端、web 走它）。所以手机端移动只改后者时，桌面端看不到——必须在移动时把这份本地
// 状态一并改掉，同时把该会话从待迁移队列里摘掉，否则桌面端迁移时会把旧归属推回去覆盖。
internal static class CodexDesktopProjects {
    private static readonly JsonSerializerOptions WriteOptions = new() {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static bool DesktopRunning() => Process.GetProcessesByName("ChatGPT").Length > 0;

    /// 返回同步结果："synced" / "no-desktop-state" / "no-legacy-project" / "failed"。
    public static string SyncMove(string home, string threadId, string appServerProjectId) {
        var path = Path.Combine(home, ".codex-global-state.json");
        if (!File.Exists(path)) return "no-desktop-state";
        JsonObject root;
        try {
            root = JsonNode.Parse(File.ReadAllText(path)) as JsonObject ?? new JsonObject();
        } catch (JsonException) {
            return "failed";
        }

        var legacyId = appServerProjectId.Length == 0 ? "" : LegacyProjectId(root, appServerProjectId);
        if (appServerProjectId.Length > 0 && legacyId.Length == 0) return "no-legacy-project";

        if (root["thread-project-assignments"] is not JsonObject assignments) {
            assignments = new JsonObject();
            root["thread-project-assignments"] = assignments;
        }
        var orders = root["sidebar-project-thread-orders"] as JsonObject;
        var projectless = root["projectless-thread-ids"] as JsonArray;

        if (legacyId.Length == 0) {
            assignments.Remove(threadId);
        } else {
            assignments[threadId] = new JsonObject { ["projectKind"] = "local", ["projectId"] = legacyId };
        }

        if (orders != null) {
            foreach (var (_, value) in orders) {
                if (value is JsonObject project && project["threadIds"] is JsonArray ids) {
                    for (var i = ids.Count - 1; i >= 0; i--) if (ids[i]?.GetValue<string>() == threadId) ids.RemoveAt(i);
                }
            }
            if (legacyId.Length > 0) {
                if (orders[legacyId] is not JsonObject target) {
                    target = new JsonObject { ["threadIds"] = new JsonArray() };
                    orders[legacyId] = target;
                }
                if (target["threadIds"] is not JsonArray targetIds) {
                    targetIds = new JsonArray();
                    target["threadIds"] = targetIds;
                }
                targetIds.Insert(0, threadId);
            }
        }

        if (projectless != null) {
            for (var i = projectless.Count - 1; i >= 0; i--) if (projectless[i]?.GetValue<string>() == threadId) projectless.RemoveAt(i);
            if (legacyId.Length == 0) projectless.Insert(0, threadId);
        }

        // app-server 已经是新归属了，桌面端不必再迁移这条，否则会把旧项目推回去。
        if (root["app-server-projects-migration-by-host"] is JsonObject hosts) {
            foreach (var (_, value) in hosts) {
                if (value is JsonObject host && host["pendingThreadAssignmentIds"] is JsonArray pending) {
                    for (var i = pending.Count - 1; i >= 0; i--) if (pending[i]?.GetValue<string>() == threadId) pending.RemoveAt(i);
                }
            }
        }

        var temp = path + ".remotetool-" + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temp, root.ToJsonString(WriteOptions));
        File.Move(temp, path, true);
        return "synced";
    }

    /// 桌面端运行时会用自己的内存状态覆盖文件，所以只有它关闭时写才有效。
    /// 这里在它关闭时把还没生效的移动一次性补上（幂等：只改与目标不一致的条目）。
    /// 返回本次同步的会话数。
    public static int SyncPending(string home, (string Id, string ProjectId)[] moves) {
        if (moves.Length == 0) return 0;
        var path = Path.Combine(home, ".codex-global-state.json");
        if (!File.Exists(path)) return 0;
        JsonObject root;
        try {
            root = JsonNode.Parse(File.ReadAllText(path)) as JsonObject ?? new JsonObject();
        } catch (JsonException) {
            return 0;
        }
        var assignments = root["thread-project-assignments"] as JsonObject;
        var stale = moves.Where(move => {
            var expected = move.ProjectId.Length == 0 ? "" : LegacyProjectId(root, move.ProjectId);
            if (move.ProjectId.Length > 0 && expected.Length == 0) return false; // 桌面端没有对应项目，跳过
            return (assignments?[move.Id]?["projectId"]?.GetValue<string>() ?? "") != expected;
        }).ToArray();
        if (stale.Length == 0) return 0;
        var synced = 0;
        foreach (var move in stale) if (SyncMove(home, move.Id, move.ProjectId) == "synced") synced++;
        return synced;
    }

    private static string LegacyProjectId(JsonObject root, string appServerProjectId) {
        if (root["app-server-project-id-by-legacy-project-id-by-host"] is not JsonObject hosts) return "";
        foreach (var (_, value) in hosts) {
            if (value is not JsonObject map) continue;
            foreach (var (legacy, mapped) in map) {
                if (mapped?.GetValue<string>() == appServerProjectId) return legacy;
            }
        }
        return "";
    }
}
