using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services;

public sealed class HermesManagement(IConfiguration configuration)
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly SemaphoreSlim compressionGate = new(1, 1);
    private readonly SemaphoreSlim contextGate = new(1, 1);
    public async Task<JsonElement> Invoke(string action, JsonElement? body, CancellationToken ct)
    {
        var home = configuration["Hermes:HomeDirectory"]
            ?? RemoteToolPaths.HermesHome;
        var source = configuration["Hermes:SourceDirectory"] ?? Path.Combine(home, "hermes-agent");
        var python = Path.Combine(source, "venv", "Scripts", "python.exe");
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
            return result.RootElement.Clone();
        }
        finally { operationGate.Release(); }
    }
}
