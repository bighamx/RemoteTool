using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

/// <summary>Serialized JSONL to a user-owned, signed macOS helper. Never replay desktop input.</summary>
public sealed class MacDesktopBridge(IConfiguration configuration) : IDisposable
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private Process process;
    public bool Configured => File.Exists(configuration["RemoteControl:MacAgentExecutable"] ?? "");

    public async Task<JsonElement> CallAsync(object request, CancellationToken ct = default)
    {
        if (!OperatingSystem.IsMacOS()) throw new PlatformNotSupportedException("Mac 桌面代理只支持 macOS");
        await gate.WaitAsync(ct);
        try {
            if (process == null || process.HasExited) {
                if (!Configured) throw new InvalidOperationException("请先构建 Mac 代理并设置 RemoteControl:MacAgentExecutable");
                process?.Dispose();
                process = Process.Start(new ProcessStartInfo(configuration["RemoteControl:MacAgentExecutable"]!) {
                    UseShellExecute = false, RedirectStandardInput = true, RedirectStandardOutput = true,
                    RedirectStandardError = true, StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8
                }) ?? throw new InvalidOperationException("无法启动 Mac 桌面代理");
                var diagnostics = process;
                _ = Task.Run(async () => { try { while (await diagnostics.StandardError.ReadLineAsync() != null) { } } catch (ObjectDisposedException) { } });
            }
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(15));
            await process.StandardInput.WriteLineAsync(JsonSerializer.Serialize(request).AsMemory(), timeout.Token);
            await process.StandardInput.FlushAsync(timeout.Token);
            var line = await process.StandardOutput.ReadLineAsync(timeout.Token) ?? throw new IOException("Mac 代理已退出");
            using var response = JsonDocument.Parse(line);
            if (response.RootElement.TryGetProperty("error", out var error)) throw new InvalidOperationException(error.GetString());
            return response.RootElement.Clone();
        } catch (Exception error) when (error is IOException or OperationCanceledException or JsonException) {
            Stop(); // A late reply must never be mistaken for the next operation's reply.
            throw;
        } finally { gate.Release(); }
    }
    public JsonElement Call(object request) => CallAsync(request).GetAwaiter().GetResult();
    private void Stop() { if (process != null) { try { if (!process.HasExited) process.Kill(true); } catch (InvalidOperationException) { } process.Dispose(); process = null; } }
    public void Dispose() { Stop(); gate.Dispose(); }
}
