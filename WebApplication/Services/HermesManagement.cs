using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services;

public sealed class HermesManagement(IConfiguration configuration)
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly SemaphoreSlim compressionGate = new(1, 1);
    private readonly SemaphoreSlim contextGate = new(1, 1);
    private readonly object maintenanceGate = new();
    private bool maintenance;
    private int mutations;
    public void BeginMaintenance() { lock (maintenanceGate) { if (maintenance || mutations != 0) throw new InvalidOperationException("有 Hermes 管理操作进行中，请稍后重试"); maintenance = true; } }
    public void EndMaintenance() { lock (maintenanceGate) maintenance = false; }
    public async Task<JsonElement> Invoke(string action, JsonElement? body, CancellationToken ct)
    {
        var mutation = !action.StartsWith("admin_") && action is not ("session_search" or "session_context" or "run_lookup" or "providers" or "model_info" or "model_reasoning" or "api_server_status");
        lock (maintenanceGate) { if (mutation) { if (maintenance) throw new InvalidOperationException("网关正在维护，请完成或取消维护后再修改配置"); mutations++; } }
        try {
        var profiles = new HermesProfiles(configuration);
        if (mutation) {
            var journal = Path.Combine(profiles.Root, "cache", "remotetool-maintenance", "status.json");
            if (File.Exists(journal)) {
                using var state = JsonDocument.Parse(File.ReadAllText(journal));
                if (state.RootElement.GetProperty("phase").GetString() is "queued" or "checking" or "waiting" or "updating" or "restarting")
                    throw new InvalidOperationException("Hermes 正在维护，请完成或取消维护后再修改配置");
            }
        }
        var home = profiles.Home;
        var source = configuration["Hermes:SourceDirectory"] ?? Path.Combine(profiles.Root, "hermes-agent");
        if (HermesProfileScope.Name != "default" && action == "enable_api_server")
            throw new InvalidOperationException("请在电脑上为此 Profile 配置 API 密钥；网关由默认 Profile 管理");
        var python = configuration["Hermes:PythonExecutable"] ?? (OperatingSystem.IsWindows()
            ? Path.Combine(source, "venv", "Scripts", "python.exe") : Path.Combine(source, "venv", "bin", "python"));
        var script = Path.Combine(Path.GetDirectoryName(RemoteControl.InteractiveProcessLauncher.GetApplicationDllPath())!, "hermes", "hermes_management.py");
        if (!File.Exists(python) || !File.Exists(script)) throw new InvalidOperationException("服务端尚未安装 Hermes 模型管理组件");
        var operationGate = action == "compress_session" ? compressionGate : action == "session_context" ? contextGate : gate;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(action == "compress_session" ? TimeSpan.FromMinutes(15) : action == "session_context" ? TimeSpan.FromSeconds(20) : TimeSpan.FromSeconds(60));
        await operationGate.WaitAsync(timeout.Token);
        try
        {
            var start = new ProcessStartInfo(python) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = source,
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
            start.ArgumentList.Add(script);
            start.Environment["HERMES_HOME"] = home;
            start.Environment["PYTHONPATH"] = source;
            start.Environment["PYTHONIOENCODING"] = "utf-8";
            // Supervised API helpers use the committed runtime; a read must never run an installer.
            start.Environment["HERMES_DISABLE_LAZY_INSTALLS"] = "1";
            using var process = Process.Start(start)!;
            var (output, errors) = (process.StandardOutput.ReadToEndAsync(), process.StandardError.ReadToEndAsync());
            await process.StandardInput.WriteAsync(JsonSerializer.Serialize(new { action, body }));
            process.StandardInput.Close();
            try { await process.WaitForExitAsync(timeout.Token); }
            catch { if (!process.HasExited) process.Kill(entireProcessTree: true); throw; }
            var errorText = await errors;
            var json = (await output).Split('\n', StringSplitOptions.RemoveEmptyEntries).LastOrDefault()?.Trim() ?? "";
            if (process.ExitCode != 0 || string.IsNullOrEmpty(json) || json[0] != '{')
                throw new InvalidOperationException(action == "session_context" ? "上下文读取失败，请检查 Hermes 运行环境" : "Hermes 管理操作失败，请检查安装版本与配置");
            using var result = JsonDocument.Parse(json);
            if (action.StartsWith("admin_") && result.RootElement.TryGetProperty("error", out var failure)) throw new InvalidOperationException(failure.GetString());
            return result.RootElement.Clone();
        }
        finally { operationGate.Release(); }
        } finally { if (mutation) lock (maintenanceGate) mutations--; }
    }
}
