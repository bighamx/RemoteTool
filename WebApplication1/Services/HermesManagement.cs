using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace ChuckieHelper.WebApi.Services;

public sealed class HermesManagement(IConfiguration configuration)
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly SemaphoreSlim compressionGate = new(1, 1);
    public async Task<JsonElement> Invoke(string action, JsonElement? body, CancellationToken ct)
    {
        var keyFile = configuration["Hermes:KeyFile"] ?? Environment.GetEnvironmentVariable("HERMES_API_KEY_FILE")
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "hermes", ".env");
        var home = Path.GetDirectoryName(Path.GetFullPath(keyFile))!;
        var source = configuration["Hermes:SourceDirectory"] ?? Path.Combine(home, "hermes-agent");
        var python = Path.Combine(source, "venv", "Scripts", "python.exe");
        var script = Path.Combine(Path.GetDirectoryName(RemoteControl.InteractiveProcessLauncher.GetApplicationDllPath())!, "hermes", "hermes_management.py");
        if (!File.Exists(python) || !File.Exists(script)) throw new InvalidOperationException("服务端尚未安装 Hermes 模型管理组件");
        var operationGate = action == "compress_session" ? compressionGate : gate;
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
            var output = process.StandardOutput.ReadToEndAsync();
            var errors = process.StandardError.ReadToEndAsync(); // Drain only; never log credential-bearing diagnostics.
            await process.StandardInput.WriteAsync(JsonSerializer.Serialize(new { action, body }));
            process.StandardInput.Close();
            try { await process.WaitForExitAsync(timeout.Token); }
            catch { if (!process.HasExited) process.Kill(entireProcessTree: true); throw; }
            await errors;
            // Runtime selection may emit startup diagnostics; only the final adapter
            // JSON is consumed. Diagnostics stay private and are never logged.
            var json = (await output).Split('\n', StringSplitOptions.RemoveEmptyEntries).LastOrDefault()?.Trim() ?? "";
            using var result = JsonDocument.Parse(json);
            if (process.ExitCode != 0) throw new InvalidOperationException("Hermes 模型设置失败，请检查服务端配置");
            return result.RootElement.Clone();
        }
        finally { operationGate.Release(); }
    }
}
