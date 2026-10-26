using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexAttachmentInputChecks
{
    public static void Run()
    {
        var journal = new JsonObject {
            ["ordinary"] = new JsonObject { ["session_id"]="s", ["key"]="ordinary-key", ["attachment_ids"]=new JsonArray("image-a"),
                ["steering_requests"]=new JsonObject { ["steer-key"]=new JsonObject { ["attachment_ids"]=new JsonArray("image-b") } } },
            ["queued"] = new JsonObject { ["session_id"]="s", ["key"]="queue-key", ["native_queue"]=true,["attachment_ids"]=new JsonArray("image-c") }
        };
        foreach(var pair in new[]{("ordinary-key","image-a"),("steer-key","image-b"),("queue-key","image-c")}) {
            var saved=System.Text.Json.Nodes.JsonNode.Parse(journal.ToJsonString())!.AsObject();
            var ids=CodexRunAttachments.Read(saved,"s",pair.Item1);
            if(ids?[0]?.ToString()!=pair.Item2 || CodexRunAttachments.Read(saved,"other-session",pair.Item1)!=null)
                throw new Exception("Durable attachment IDs must follow the exact session and request across restart");
            ids.Clear();
            if(CodexRunAttachments.Read(saved,"s",pair.Item1)?.Count!=1) throw new Exception("History must not mutate attachment journal state");
        }
        var root = Path.Combine(Path.GetTempPath(), "codex-attachment-input-" + Guid.NewGuid().ToString("N"));
        var folder = Path.Combine(root, "session");
        Directory.CreateDirectory(folder);
        try {
            var path = Path.Combine(folder, "image.jpg");
            File.WriteAllBytes(path, [1, 2, 3]);
            var input = CodexAttachmentInput.Build("hello", new JsonArray(path), root, "session");
            if (input.Count != 2 || input[1]!["type"]!.ToString() != "localImage") throw new Exception("Readable current-session image must be accepted");
            if (input[0]!["text"]!.ToString() != "hello") throw new Exception("Native images must not append paths or markers to user text");
            var document = Path.Combine(folder,"document.txt");
            File.WriteAllText(document,"document content");
            var mixed = CodexAttachmentInput.Build("hello",new JsonArray(path,document),root,"session");
            if (!mixed[0]!["text"]!.ToString().Contains(document) || mixed[0]!["text"]!.ToString().Contains(path))
                throw new Exception("Only non-image file references need text fallback");
            void Reject(string file, string expected) {
                try { CodexAttachmentInput.Build("hello", new JsonArray(file), root, "session"); }
                catch (CodexError error) { if (error.Message.Contains(expected)) return; throw; }
                throw new Exception("Attachment was incorrectly accepted");
            }
            Reject(Path.Combine(root, "other", "image.jpg"), "不属于当前会话");
            Reject(Path.Combine(folder, "missing.jpg"), "已不存在");
            Reject(folder + Path.DirectorySeparatorChar, "不是文件");
            if (OperatingSystem.IsWindows()) {
                var file = new FileInfo(path);
                var original = file.GetAccessControl();
                var denied = file.GetAccessControl();
                denied.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User!, FileSystemRights.ReadData, AccessControlType.Deny));
                try { file.SetAccessControl(denied); Reject(path, "读取权限不足"); }
                finally { file.SetAccessControl(original); }
            }
            Console.WriteLine("Codex attachment input checks passed: readable images, session isolation, missing file, directory, and read permissions.");
        } finally { Directory.Delete(root, true); }
    }
}
