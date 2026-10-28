using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexProjects
{
    internal static StringComparer PathComparer => OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
    private static StringComparison PathComparison => OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
    public static string RootSignature(JsonArray roots) => string.Join('\n', roots.Select(root => {
        var path = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root.S("path")));
        return OperatingSystem.IsWindows() ? path.ToLowerInvariant() : path;
    }).Distinct(PathComparer).Order(PathComparer));
    public static string DirectoryPath(string path) {
        if (!Path.IsPathFullyQualified(path)) throw new CodexError("请选择电脑上的绝对目录路径");
        var full = Path.GetFullPath(path);
        if (!Directory.Exists(full)) throw new CodexError("工作目录不存在，请先选择或新建文件夹");
        return full;
    }
    /** project/list 结果 5 分钟缓存：项目集极少变化，列表请求不必每次都走 rpc。 */
    private static readonly object listGate = new();
    private static (DateTime At, JsonArray Rows)? listCache;
    public static async Task<JsonArray> CachedList(CodexRpc rpc) {
        lock(listGate) if (listCache is { } known && DateTime.UtcNow - known.At < TimeSpan.FromMinutes(5)) return known.Rows;
        try {
            var rows = await List(rpc);
            lock(listGate) listCache = (DateTime.UtcNow, rows);
            return rows;
        } catch (Exception error) when (error is CodexError or InvalidOperationException or TimeoutException) {
            // rpc 未就绪/超时：退化为无项目匹配（按 cwd 叶子分组），不阻塞列表。
            lock(listGate) if (listCache is { } stale) return stale.Rows;
            return new JsonArray();
        }
    }
    /** 会话分组名：cwd 命中项目 root（或位于其下）→ 项目名；否则 cwd 叶子段。
        与桌面端一致按工作目录聚合；Windows 路径大小写不敏感，须按目录边界比较。 */
    public static (string Name, bool FromProject) GroupName(string cwd, JsonArray projects) {
        if (string.IsNullOrWhiteSpace(cwd)) return (null, false);
        string full;
        try { full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(cwd)); } catch (Exception error) when (error is ArgumentException or IOException) { return (null, false); }
        string bestName = null; var bestLength = -1;
        foreach (var project in projects) {
            var name = project?.S("name");
            if (string.IsNullOrWhiteSpace(name)) continue;
            foreach (var root in project.A("roots").Select(r => r.S("path")).Where(p => p.Length > 0)) {
                string normalized;
                try { normalized = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)); } catch (Exception error) when (error is ArgumentException or IOException) { continue; }
                if (normalized.Length <= bestLength) continue;
                var hit = full.Equals(normalized, PathComparison)
                    || full.StartsWith(Path.EndsInDirectorySeparator(normalized) ? normalized : normalized + Path.DirectorySeparatorChar, PathComparison);
                if (hit) { bestName = name; bestLength = normalized.Length; }
            }
        }
        if (bestName != null) return (bestName, true);
        var leaf = Path.GetFileName(full);
        return (leaf.Length > 0 ? leaf : full, false);
    }
    public static async Task<JsonArray> List(CodexRpc rpc) {
        var rows = new JsonArray(); string cursor = null;
        do {
            var request = Obj(("limit", 100));
            if (cursor != null) request["cursor"] = cursor;
            var result = await rpc.Call("project/list", request);
            foreach (var project in result.A("data")) rows.Add(project!.DeepClone());
            var next = result.S("nextCursor");
            if (next == cursor) break;
            cursor = next.Length == 0 ? null : next;
        } while (cursor != null);
        return rows;
    }
    public static string ProjectDirectory(JsonNode project, string requested) {
        var roots = project.A("roots").Select(r => r.S("path")).Where(p => p.Length > 0).ToArray();
        if (roots.Length == 0) throw new CodexError("此项目没有本机工作目录");
        var path = requested.Length > 0 ? DirectoryPath(requested) : DirectoryPath(roots[0]);
        if (!roots.Any(root => Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)).Equals(Path.TrimEndingDirectorySeparator(path), PathComparison)))
            throw new CodexError("所选工作目录不属于此项目");
        return path;
    }
}
