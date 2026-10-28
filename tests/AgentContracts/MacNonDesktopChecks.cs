using System.Text;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
using RemoteTool.WebApi.Services.RemoteControl;

internal static class MacNonDesktopChecks
{
    public static void Run() {
        foreach (var command in new[] { "{ printf hello; }", "  { echo 中文; }", "{\"value\":42}" }) {
            var frame = MacTerminalInput.Encode(command)!.Value;
            if (frame.Opcode != 1 || Encoding.UTF8.GetString(frame.Payload) != command + "\r")
                throw new Exception("Shell blocks and raw JSON text must reach the terminal unchanged");
        }
        var input = MacTerminalInput.Encode("""{"type":"input","data":"中文\u0003"}""")!.Value;
        if (Encoding.UTF8.GetString(input.Payload) != "中文\u0003") throw new Exception("Structured input must preserve Unicode and Ctrl+C without adding Enter");
        var resize = MacTerminalInput.Encode("""{"type":"resize","cols":999,"rows":1}""")!.Value;
        if (resize.Opcode != 2 || !resize.Payload.SequenceEqual(new byte[] { 244, 1, 5, 0 })) throw new Exception("PTY resize must stay bounded and little endian");
        if (MacTerminalInput.Encode("""{"type":"future"}""") != null) throw new Exception("Unknown control frames must not execute as commands");

        var filesystemRoot = Path.GetPathRoot(Path.GetTempPath())!;
        if (CodexProjects.GroupName(filesystemRoot, new()).Name != filesystemRoot) throw new Exception("Root cwd must have a group name without indexing an empty path");
        var project = new JsonObject { ["name"] = "Root project", ["roots"] = new JsonArray(new JsonObject { ["path"] = filesystemRoot }) };
        if (!CodexProjects.GroupName(Path.GetTempPath(), new JsonArray(project)).FromProject) throw new Exception("A root project must match child directories");
        var lower = new JsonArray(new JsonObject { ["path"] = Path.Combine(filesystemRoot, "sample") });
        var upper = new JsonArray(new JsonObject { ["path"] = Path.Combine(filesystemRoot, "Sample") });
        if ((CodexProjects.RootSignature(lower) == CodexProjects.RootSignature(upper)) != OperatingSystem.IsWindows())
            throw new Exception("Unix project paths must not be lowercased into one project identity");
        Console.WriteLine("Terminal shell-block/Unicode/resize and project root/case checks passed");
    }
}
