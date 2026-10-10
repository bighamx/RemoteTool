using System.Reflection;
using System.Runtime.Loader;
using System.Text.Json;

var dll = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (_, name) => {
    var path = Path.Combine(Path.GetDirectoryName(dll)!, name.Name + ".dll");
    return File.Exists(path) ? AssemblyLoadContext.Default.LoadFromAssemblyPath(path) : null;
};
var assembly = AssemblyLoadContext.Default.LoadFromAssemblyPath(dll);
var agent = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent", true)!;
var ctor = agent.GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!;
var file = Path.GetTempFileName();
try {
    foreach (var home in new string?[] { null, "", "   ", "relative-home" }) {
        File.WriteAllText(file, JsonSerializer.Serialize(new { home }));
        try { ctor.Invoke([file]); throw new Exception("Invalid home was accepted"); }
        catch (TargetInvocationException e) when (e.InnerException is InvalidOperationException error && error.Message.Contains("non-empty absolute path")) { }
    }
    Console.WriteLine("PASS: missing/empty/whitespace/relative home rejected with explicit startup error");
} finally { File.Delete(file); }
if (args.Length > 1 && args[1] == "sid") {
    var launcher = assembly.GetType("RemoteTool.WebApi.Services.RemoteControl.InteractiveProcessLauncher", true)!;
    var sid = (string)launcher.GetMethod("GetInteractiveUserSid")!.Invoke(null, null)!;
    if (sid is "S-1-5-18" or "S-1-5-19" or "S-1-5-20") throw new Exception("Selected a service identity");
    Console.WriteLine("PASS interactive user SID: " + sid);
}
if (args.Length > 2 && args[1] == "upgrade") await CodexRuntimeUpgradeChecks.Run(assembly, Path.GetFullPath(args[2]));
