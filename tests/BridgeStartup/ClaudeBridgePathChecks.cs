using System.Reflection;

internal static class ClaudeBridgePathChecks
{
    public static void Run(Assembly assembly)
    {
        var temporary = Path.Combine(Path.GetTempPath(), "remotetool-claude-paths-" + Guid.NewGuid().ToString("N"));
        var root = Path.Combine(temporary, "源码 path");
        Directory.CreateDirectory(root);
        var find = assembly.GetType("RemoteTool.WebApi.Services.ClaudeBridge", true)!
            .GetMethod("FindScript", BindingFlags.Static | BindingFlags.NonPublic)!;
        string Resolve(string binary, string content) => (string)find.Invoke(null, [binary, content, true])!;
        string Script(string folder, bool installed)
        {
            Directory.CreateDirectory(folder);
            var script = Path.Combine(folder, "server.mjs");
            File.WriteAllText(script, "// isolated bridge fixture");
            if (installed)
            {
                var sdk = Path.Combine(folder, "node_modules", "@anthropic-ai", "claude-agent-sdk");
                Directory.CreateDirectory(sdk);
                File.WriteAllText(Path.Combine(sdk, "package.json"), "{}");
                File.WriteAllText(Path.Combine(sdk, "sdk.mjs"), "// isolated SDK fixture");
            }
            return script;
        }
        void Check(string actual, string expected, string message)
        {
            if (actual != expected) throw new Exception(message);
        }
        try
        {
            var project = Path.Combine(root, "WebApplication");
            var binary = Path.Combine(project, "bin", "Debug", "net8.0");
            Directory.CreateDirectory(binary);
            File.WriteAllText(Path.Combine(project, "WebApplication.csproj"), "<Project />");
            var output = Script(Path.Combine(binary, "claude"), false);
            var source = Script(Path.Combine(project, "Tools", "ClaudeBridge"), true);
            Check(Resolve(binary, binary), source, "A bin-hosted source install must discover the SDK in its project ancestor");
            Check(Resolve(binary, project), source, "Source-root hosting must retain its installed SDK");
            Check(Resolve(root, binary), source, "Content-root ancestors must work when the executable lives elsewhere");

            var sdk = Path.Combine(binary, "claude", "node_modules", "@anthropic-ai", "claude-agent-sdk");
            Directory.CreateDirectory(sdk);
            File.WriteAllText(Path.Combine(sdk, "package.json"), "{}");
            Check(Resolve(binary, binary), source, "A partial runtime install must allow the complete source install");
            File.WriteAllText(Path.Combine(sdk, "sdk.mjs"), "// isolated SDK fixture");
            Check(Resolve(binary, binary), output, "A complete runtime install must keep priority");

            var published = Path.Combine(root, "published");
            var publishedScript = Script(Path.Combine(published, "claude"), true);
            Check(Resolve(published, published), publishedScript, "Published deployments must work independently of a checkout");
            Check(Resolve(binary, published), output, "The executable's complete runtime keeps priority over the content root");
            File.Delete(Path.Combine(sdk, "sdk.mjs"));
            Check(Resolve(binary, published), publishedScript, "An explicit installed content root takes priority over an ancestor fallback");

            File.Delete(Path.Combine(published, "claude", "node_modules", "@anthropic-ai", "claude-agent-sdk", "sdk.mjs"));
            try { Resolve(published, published); throw new Exception("An incomplete SDK was accepted"); }
            catch (TargetInvocationException error) when (error.InnerException is InvalidOperationException missing)
            {
                if (!missing.Message.Contains(Path.GetDirectoryName(publishedScript)!) || !missing.Message.Contains("npm ci --omit=dev --omit=optional"))
                    throw new Exception("The installation error must name the actual bridge directory and command");
            }
            try { Resolve(root, root); throw new Exception("Missing bridge files were accepted"); }
            catch (TargetInvocationException error) when (error.InnerException is InvalidOperationException missing && missing.Message.Contains("桥接文件未发布")) { }
            Console.WriteLine("PASS: Claude SDK discovery from source, nested bin, separate content root and published layouts; partial installs and exact diagnostics");
        }
        finally { Directory.Delete(temporary, true); }
    }
}
