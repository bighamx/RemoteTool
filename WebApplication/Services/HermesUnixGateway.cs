using System.Diagnostics;
using System.Text;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// Linux/macOS 上的 Hermes 网关重启：用官方 CLI 的 systemd/launchd 用户服务，
/// 不依赖 Windows 计划任务（那是 Windows 的交互式会话方案）。
/// </summary>
internal static class HermesUnixGateway
{
    public sealed record Result(bool Ok, string Message, int Port);

    public static async Task<Result> Restart(string python, string home, CancellationToken ct)
    {
        var port = Port(home);
        var (installed, installText) = await Run(python, home, new[] { "-m", "hermes_cli.main", "gateway", "install" }, ct);
        // install 在已安装时会返回非零并提示，忽略；restart 才是关键动作。
        var (restarted, restartText) = await Run(python, home, new[] { "-m", "hermes_cli.main", "gateway", "restart" }, ct);
        if (!restarted)
        {
            var (started, startText) = await Run(python, home, new[] { "-m", "hermes_cli.main", "gateway", "start" }, ct);
            if (!started) return new(false, Trim(restartText + " " + startText + " " + installText), port);
        }
        for (var attempt = 0; attempt < 30; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            if (await Probe(port, ct)) return new(true, "网关已重启，API 已就绪", port);
            await Task.Delay(1000, ct);
        }
        return new(false, "网关已启动，但 API 端口未在 30 秒内响应，请查看 journalctl --user -u hermes-gateway", port);
    }

    public static int Port(string home)
    {
        try
        {
            var env = Path.Combine(home, ".env");
            if (File.Exists(env))
                foreach (var line in File.ReadLines(env))
                {
                    var parts = line.Split('=', 2);
                    if (parts.Length == 2 && parts[0].Trim() == "API_SERVER_PORT" && int.TryParse(parts[1].Trim(), out var value) && value > 0 && value < 65536)
                        return value;
                }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        return 8642;
    }

    private static async Task<bool> Probe(int port, CancellationToken ct)
    {
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
            using var response = await client.GetAsync($"http://127.0.0.1:{port}/v1/capabilities", ct);
            return response.IsSuccessStatusCode || (int)response.StatusCode == 401;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or IOException) { return false; }
    }

    private static async Task<(bool Ok, string Text)> Run(string python, string home, string[] args, CancellationToken ct)
    {
        var start = new ProcessStartInfo(python)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var arg in args) start.ArgumentList.Add(arg);
        start.Environment["HERMES_HOME"] = home;
        start.Environment["PYTHONIOENCODING"] = "utf-8";
        using var process = Process.Start(start) ?? throw new IOException("无法启动 Hermes CLI");
        var output = process.StandardOutput.ReadToEndAsync(ct);
        var error = process.StandardError.ReadToEndAsync(ct);
        await process.WaitForExitAsync(ct);
        var text = (await output) + (await error);
        return (process.ExitCode == 0, Trim(text));
    }

    private static string Trim(string text) => text.Length <= 400 ? text.Trim() : text[^400..].Trim();
}
