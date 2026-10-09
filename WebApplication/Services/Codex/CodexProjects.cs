using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexProjects
{
    public static string RootSignature(JsonArray roots) => string.Join('\n', roots.Select(root => Path.TrimEndingDirectorySeparator(Path.GetFullPath(root.S("path"))).ToLowerInvariant()).Distinct().Order());
    public static string DirectoryPath(string path) {
        if (!Path.IsPathFullyQualified(path)) throw new CodexError("请选择电脑上的绝对目录路径");
        var full = Path.GetFullPath(path);
        if (!Directory.Exists(full)) throw new CodexError("工作目录不存在，请先选择或新建文件夹");
        return full;
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
        if (!roots.Any(root => Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)).Equals(Path.TrimEndingDirectorySeparator(path), StringComparison.OrdinalIgnoreCase)))
            throw new CodexError("所选工作目录不属于此项目");
        return path;
    }
}
