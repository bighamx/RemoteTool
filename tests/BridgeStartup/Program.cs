using System.Reflection;
using System.Runtime.Loader;
using System.Text.Json;

if (args.FirstOrDefault() == "app-server" && Environment.GetEnvironmentVariable("REMOTETOOL_NATIVE_RECOVERY_FIXTURE") == "1") {
    await CodexNativeRecoveryChecks.FakeCli(); return;
}

var dll = Path.GetFullPath(args[0]);
AssemblyLoadContext.Default.Resolving += (_, name) => {
    var path = Path.Combine(Path.GetDirectoryName(dll)!, name.Name + ".dll");
    return File.Exists(path) ? AssemblyLoadContext.Default.LoadFromAssemblyPath(path) : null;
};
var assembly = AssemblyLoadContext.Default.LoadFromAssemblyPath(dll);
if (args.Contains("--claude-config-only")) { await ClaudeConfigurationChecks.Run(assembly); await ClaudeBridgeRestartChecks.Run(assembly); return; }
ClaudeBridgePathChecks.Run(assembly);
if (args.Contains("--claude-paths-only")) return;
await ClaudeConfigurationChecks.Run(assembly);
await ClaudeBridgeRestartChecks.Run(assembly);
await HermesSessionCreateChecks.Run(assembly);
if (args.Contains("--hermes-create-only")) return;
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
await CodexDesktopRecoveryChecks.Run(assembly);
await CodexRunReconciliationChecks.Run(assembly);
await CodexNativeRecoveryChecks.Run(assembly);
await CodexShutdownChecks.Run(assembly);
if (args.Length > 1 && args[1] == "sid") {
    var launcher = assembly.GetType("RemoteTool.WebApi.Services.RemoteControl.InteractiveProcessLauncher", true)!;
    var sid = (string)launcher.GetMethod("GetInteractiveUserSid")!.Invoke(null, null)!;
    if (sid is "S-1-5-18" or "S-1-5-19" or "S-1-5-20") throw new Exception("Selected a service identity");
    Console.WriteLine("PASS interactive user SID: " + sid);
}
if (args.Length > 2 && args[1] == "upgrade") await CodexRuntimeUpgradeChecks.Run(assembly, Path.GetFullPath(args[2]));
if (args.Length > 2 && args[1] == "selection") await CodexModelSelectionChecks.Run(assembly, Path.GetFullPath(args[2]));
