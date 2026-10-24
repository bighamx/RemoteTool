using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

public sealed class HermesAdminFiles(HermesProfiles profiles)
{
    private readonly object gate = new();
    private static readonly Dictionary<string, (string Path, string Name)> documents = new() {
        ["memory"] = ("memories/MEMORY.md", "记忆"), ["user"] = ("memories/USER.md", "用户偏好与指令"), ["soul"] = ("SOUL.md", "人格 · SOUL.md")
    };
    private string Target(string id) {
        if (!documents.TryGetValue(id, out var doc)) throw new ArgumentException("不支持的文件");
        var home = Path.GetFullPath(profiles.Home);
        var path = Path.Combine(home, doc.Path.Replace('/', Path.DirectorySeparatorChar));
        for (var current = path; current != home; current = Path.GetDirectoryName(current)!)
            if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidOperationException("不支持编辑链接文件或目录");
        return path;
    }
    private static byte[] Bytes(string path) {
        if (!File.Exists(path)) return null;
        if (new FileInfo(path).Length > 1024 * 1024) throw new InvalidOperationException("文件超过 1 MiB，请在电脑上编辑");
        return File.ReadAllBytes(path);
    }
    private static string Revision(byte[] bytes) => bytes == null ? "missing" : Convert.ToHexString(SHA256.HashData(bytes));
    private static string Text(byte[] bytes) => bytes == null ? "" : new UTF8Encoding(false, true).GetString(bytes).TrimStart('\uFEFF');
    private string Backups(string id) {
        var home = Path.GetFullPath(profiles.Home);
        var path = Path.Combine(home, "backups", "remotetool-editor", id);
        for (var current = path; current != home; current = Path.GetDirectoryName(current)!)
            if (Directory.Exists(current) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("备份目录不能是链接");
        return path;
    }
    public JsonObject List() => new() { ["profile"] = HermesProfileScope.Name, ["data"] = new JsonArray(documents.Select(pair => (JsonNode)new JsonObject { ["id"] = pair.Key, ["name"] = pair.Value.Name, ["path"] = pair.Value.Path }).ToArray()) };
    public JsonObject Read(string id) {
        lock (gate) {
            var targetPath = Target(id);
            var bytes = Bytes(targetPath); var folder = Backups(id);
            var backups = Directory.Exists(folder) ? Directory.EnumerateFiles(folder, "*.md").Where(path => (File.GetAttributes(path) & FileAttributes.ReparsePoint) == 0).OrderDescending().Take(50)
                .Select(path => (JsonNode)new JsonObject { ["id"] = Path.GetFileNameWithoutExtension(path), ["at"] = File.GetLastWriteTimeUtc(path).ToString("O") }).ToArray() : Array.Empty<JsonNode>();
            return new() { ["profile"] = HermesProfileScope.Name, ["id"] = id, ["path"] = documents[id].Path, ["fullPath"] = targetPath, ["content"] = Text(bytes), ["revision"] = Revision(bytes), ["exists"] = bytes != null, ["backups"] = new JsonArray(backups) };
        }
    }
    public JsonObject Save(string id, string revision, string content, bool overwrite, string backupId = null) {
        lock (gate) {
            var path = Target(id); var before = Bytes(path);
            if (!overwrite && Revision(before) != revision) throw new AdminFileConflict();
            byte[] after;
            if (backupId != null) {
                if (!System.Text.RegularExpressions.Regex.IsMatch(backupId, "^[0-9]{17}-[a-f0-9]{32}$")) throw new ArgumentException("无效备份");
                var backupPath = Path.Combine(Backups(id), backupId + ".md");
                if (File.Exists(backupPath) && (File.GetAttributes(backupPath) & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("备份不能是链接");
                after = Bytes(backupPath) ?? throw new FileNotFoundException("备份不存在");
            } else {
                after = new UTF8Encoding(false, true).GetBytes(content ?? "");
                if (after.Length > 1024 * 1024) throw new ArgumentException("文件超过 1 MiB");
            }
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            File.WriteAllBytes(temporary, after);
            try {
                // Check again immediately before replacement; never silently overwrite an external edit.
                if (Revision(Bytes(path)) != Revision(before)) throw new AdminFileConflict();
                if (before != null) {
                    var folder = Backups(id); Directory.CreateDirectory(folder);
                    File.Replace(temporary, path, Path.Combine(folder, DateTime.UtcNow.ToString("yyyyMMddHHmmssfff") + "-" + Guid.NewGuid().ToString("N") + ".md"));
                } else File.Move(temporary, path, false);
            } finally { if (File.Exists(temporary)) File.Delete(temporary); }
            return Read(id);
        }
    }
}
public sealed class AdminFileConflict : Exception;
