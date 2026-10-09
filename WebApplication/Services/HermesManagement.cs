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
        await operationGate.WaitAsync(ct);
        try
        {
            var start = new ProcessStartInfo(python) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = source,
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
            start.ArgumentList.Add(script);
            start.Environment["HERMES_HOME"] = home;
            start.Environment["PYTHONPATH"] = source;
            start.Environment["PYTHONIOENCODING"] = "utf-8";
            using var process = Process.Start(start)!;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(action == "compress_session" ? TimeSpan.FromMinutes(15) : TimeSpan.FromSeconds(60));
            var (output, errors) = (process.StandardOutput.ReadToEndAsync(), process.StandardError.ReadToEndAsync());
            await process.StandardInput.WriteAsync(JsonSerializer.Serialize(new { action, body }));
            process.StandardInput.Close();
            try { await process.WaitForExitAsync(timeout.Token); }
            catch { if (!process.HasExited) process.Kill(entireProcessTree: true); throw; }
            var errorText = await errors;
            var json = (await output).Split('\n', StringSplitOptions.RemoveEmptyEntries).LastOrDefault()?.Trim() ?? "";
            if (process.ExitCode != 0 || string.IsNullOrEmpty(json) || json[0] != '{')
                throw new InvalidOperationException("Hermes 模型设置失败: " + (errorText.Length > 400 ? errorText[^400..] : errorText) + " | " + (json.Length > 200 ? json[..200] : json));
            using var result = JsonDocument.Parse(json);
            return result.RootElement.Clone();
        }
        finally { operationGate.Release(); }
    }
}
