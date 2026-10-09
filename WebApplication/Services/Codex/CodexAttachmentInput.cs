using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexAttachmentInput
{
    public static JsonArray Build(string text, JsonArray paths, string root, string session)
    {
        if (paths.Count > 8) throw new CodexError("每条消息最多 8 个附件");
        if (!System.Text.RegularExpressions.Regex.IsMatch(session, "^[a-zA-Z0-9_-]{1,160}$")) throw new CodexError("无效会话标识");
        var folder = Path.GetFullPath(Path.Combine(root, session)) + Path.DirectorySeparatorChar;
        var files = new List<string>();
        var images = new JsonArray();
        foreach (var value in paths) {
            var path = Path.GetFullPath(value!.ToString());
            if (!path.StartsWith(folder, OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal) || !File.Exists(path)) throw new CodexError("附件不属于当前会话或已不存在");
            files.Add(path);
            if (Path.GetExtension(path).ToLowerInvariant() is ".png" or ".jpg" or ".jpeg" or ".webp" or ".gif") images.Add(Obj(("type", "localImage"), ("path", path)));
        }
        if (files.Count > 0) text += "\n\n附件文件：\n" + string.Join('\n', files.Select(path => "\"" + path + "\""));
        var input = new JsonArray(Obj(("type", "text"), ("text", text), ("text_elements", new JsonArray())));
        foreach (var image in images) input.Add(image!.DeepClone());
        return input;
    }
}
