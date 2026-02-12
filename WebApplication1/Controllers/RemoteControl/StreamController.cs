using System.Runtime.InteropServices;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services.RemoteControl;
using System.Diagnostics;
using System.Text;
namespace ChuckieHelper.WebApi.Controllers.RemoteControl;

[ApiController]
[Route("api/[controller]")]
[Authorize]
public class StreamController : ControllerBase

{
    private readonly SystemService _systemService;
    private const string Boundary = "frame";

    private IActionResult ApiError(string message)
            => BadRequest(new { message });
    public StreamController(SystemService systemService)
    {
        _systemService = systemService;
    }

    [HttpGet("legacy")]
    public async Task LegacyGetStream(CancellationToken cancellationToken)
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            Response.StatusCode = 503;
            await Response.WriteAsync("远程桌面视频流在 Linux 上已禁用", cancellationToken);
            return;
        }
        var response = Response;
        response.Headers.Append("Content-Type", $"multipart/x-mixed-replace; boundary={Boundary}");

        var boundaryBytes = Encoding.ASCII.GetBytes($"\r\n--{Boundary}\r\nContent-Type: image/jpeg\r\n\r\n");
        const int targetFrameTimeMs = 33; // 30 FPS = 33.33ms per frame

        while (!cancellationToken.IsCancellationRequested)
        {
            var frameStopwatch = Stopwatch.StartNew();

            try
            {
                // 使用异步版本以支持 Session 0
                var imageBytes = await _systemService.CaptureScreenAsync();

                if (imageBytes.Length > 0)
                {
                    await response.Body.WriteAsync(boundaryBytes, cancellationToken);
                    await response.Body.WriteAsync(imageBytes, cancellationToken);
                    await response.Body.FlushAsync(cancellationToken);
                }

                frameStopwatch.Stop();
                var frameTimeMs = (int)frameStopwatch.ElapsedMilliseconds;

                // Only wait if frame transmission is faster than target FPS (30 FPS)
                if (frameTimeMs < targetFrameTimeMs)
                {
                    var delayMs = targetFrameTimeMs - frameTimeMs;
                    await Task.Delay(delayMs, cancellationToken);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception)
            {
                // Ignore transient errors
                await Task.Delay(500, cancellationToken);
            }
        }
    }

    // Cache for detected hardware encoder and FFmpeg info (static to persist across requests)
    private static string? _cachedEncoder;
    private static string? _cachedEncoderParams;
    private static string? _cachedScreenCaptureInput;
    private static Version? _cachedFfmpegVersion;
    private static readonly object _cacheLock = new();

    // Allowed values for input validation
    private static readonly HashSet<string> AllowedResolutions = new()
    {
        "640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"
    };

    private static readonly HashSet<string> AllowedBitrates = new()
    {
        "500k", "1M", "2M", "3M", "4M", "5M", "8M", "10M", "15M", "20M"
    };

    /// <summary>
    /// 帧率（60fps 可降低 MSE 解码管线延迟：3帧缓冲从 100ms 降到 50ms）
    /// </summary>
    private const int Framerate = 60;

    [HttpGet]
    public async Task GetStream(
        [FromQuery] string resolution = "1280x720",
        [FromQuery] string bitrate = "3M",
        [FromQuery] string maxrate = "5M",
        [FromQuery] string crf = "18",
        CancellationToken cancellationToken = default)
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            Response.StatusCode = 503;
            await Response.WriteAsync("远程桌面视频流在 Linux 上已禁用", cancellationToken);
            return;
        }
        Process? process = null;
        Task? stderrTask = null;

        try
        {
            // Input validation to prevent command injection
            if (!AllowedResolutions.Contains(resolution ?? "1280x720"))
            {
                resolution = "1280x720";
                Console.WriteLine($"Invalid resolution, using default: {resolution}");
            }

            if (!AllowedBitrates.Contains(bitrate ?? "3M"))
            {
                bitrate = "3M";
                Console.WriteLine($"Invalid bitrate, using default: {bitrate}");
            }

            if (!AllowedBitrates.Contains(maxrate ?? "5M"))
            {
                maxrate = "5M";
                Console.WriteLine($"Invalid maxrate, using default: {maxrate}");
            }

            if (!int.TryParse(crf, out var crfValue) || crfValue < 0 || crfValue > 51)
            {
                crf = "18";
                crfValue = 18;
                Console.WriteLine($"Invalid CRF, using default: {crf}");
            }

            // Check if ffmpeg is available
            if (!await CheckFfmpegAvailableAsync(cancellationToken))
            {
                Response.StatusCode = 503;
                await Response.WriteAsync("FFmpeg not available", cancellationToken);
                return;
            }

            // Set response headers
            var response = Response;
            response.Headers.Append("Content-Type", "video/mp4");
            response.Headers.Append("Cache-Control", "no-cache, no-store");
            response.Headers.Append("Connection", "keep-alive");
            response.Headers.Append("X-Content-Type-Options", "nosniff");

            // 先确定编码器，再根据编码器类型选择截屏方式
            var (encoder, encoderParams) = await GetEncoderAsync(crf, cancellationToken);
            var (screenCaptureInput, captureMethod) = await GetScreenCaptureInputAsync(encoder, cancellationToken);

            // Calculate appropriate buffer size
            var bitrateKbps = ParseBitrateToKbps(bitrate);
            var bufferSize = $"{bitrateKbps / 2}k";

            // libx264 特有参数（硬件编码器有各自的参数，已在 encoderParams 中）
            var profileArg = encoder == "libx264" ? "-profile:v baseline -level 3.1" : "";
            var tuneArg = encoder == "libx264" ? "-tune zerolatency" : "";

            var isSession0 = InteractiveProcessLauncher.IsRunningInSession0;

            // 根据截屏方式决定滤镜链和像素格式：
            // ddagrab + 硬件编码器：帧始终在 GPU 内存，不做 CPU 侧处理（零开销）
            // gdigrab + 任意编码器：CPU 帧，需要 scale + yuv420p 转换
            string vfArg, pixFmtArg;
            if (captureMethod == "ddagrab")
            {
                // GPU 路径：ddagrab 产出 d3d11 帧 → 直送硬件编码器
                // 不做 hwdownload、不做 CPU 缩放、不做格式转换
                vfArg = "";
                pixFmtArg = "";
            }
            else
            {
                // CPU 路径：gdigrab → scale → yuv420p → 编码
                vfArg = $"-vf scale={resolution}";
                pixFmtArg = "-pix_fmt yuv420p";
            }

            // GOP = 帧率的一半（每 0.5 秒一个关键帧，平衡延迟与压缩率）
            var gopSize = Framerate / 2;
            // frag_duration = 一帧时长（微秒），每帧立即输出一个分片
            var fragDuration = 1_000_000 / Framerate;

            // 构建 FFmpeg 参数（不含输出目标，根据模式分别追加）
            var ffmpegArgsBase = $"-hide_banner -loglevel error " +
                           $"-probesize 32 -analyzeduration 0 -fflags +nobuffer+genpts " +
                           $"{screenCaptureInput} " +
                           $"{vfArg} {pixFmtArg} ".TrimEnd() + " " +
                           $"-c:v {encoder} {encoderParams} -b:v {bitrate} -maxrate {maxrate} -bufsize {bufferSize} " +
                           $"-g {gopSize} -keyint_min {gopSize} -bf 0 {profileArg} {tuneArg} " +
                           $"-flush_packets 1 " +
                           $"-frag_duration {fragDuration} " +
                           $"-movflags +frag_keyframe+empty_moov+default_base_moof " +
                           $"-f mp4";

            Console.WriteLine($"encoder: {encoder}, capture: {captureMethod}, quality: {resolution}, {bitrate}, CRF {crf}, session0: {isSession0}");

            if (isSession0)
            {
                // Session 0 模式：TCP 直连方案
                // Agent 只负责在交互式会话中启动 FFmpeg，FFmpeg 通过 TCP 直接向 IIS 发送视频数据
                // 消除 Agent 中转带来的延迟，性能等同于直连模式
                System.Net.Sockets.TcpListener? tcpListener = null;
                int ffmpegPid = 0;

                try
                {
                    // 1. 在 IIS 进程中开启 TCP 监听（随机端口）
                    tcpListener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
                    tcpListener.Start(1);
                    var port = ((System.Net.IPEndPoint)tcpListener.LocalEndpoint).Port;

                    // 2. 构建 FFmpeg 参数：输出到 TCP
                    var ffmpegArgs = $"{ffmpegArgsBase} tcp://127.0.0.1:{port}";
                    Console.WriteLine($"Session 0: FFmpeg TCP 直连模式，端口 {port}");
                    Console.WriteLine($"Starting FFmpeg {ffmpegArgs}");

                    // 3. 通过 Agent 在交互式会话中启动 FFmpeg
                    var (pid, launchError) = await DesktopAgent.LaunchFFmpegInAgentAsync(ffmpegArgs, cancellationToken);
                    if (pid == 0)
                    {
                        Console.WriteLine($"通过 DesktopAgent 启动 FFmpeg 失败: {launchError}");
                        if (!Response.HasStarted)
                        {
                            Response.StatusCode = 500;
                            await Response.WriteAsync($"DesktopAgent FFmpeg error: {launchError}", cancellationToken);
                        }
                        return;
                    }
                    ffmpegPid = pid;

                    // 4. 等待 FFmpeg 连接到 TCP 端口
                    using var acceptCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    acceptCts.CancelAfter(10000); // 10 秒超时

                    System.Net.Sockets.TcpClient tcpClient;
                    try
                    {
                        tcpClient = await tcpListener.AcceptTcpClientAsync(acceptCts.Token);
                    }
                    catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                    {
                        Console.WriteLine("等待 FFmpeg TCP 连接超时");
                        if (!Response.HasStarted)
                        {
                            Response.StatusCode = 500;
                            await Response.WriteAsync("FFmpeg failed to connect via TCP (timeout)", cancellationToken);
                        }
                        return;
                    }

                    using (tcpClient)
                    {
                        tcpClient.NoDelay = true; // 禁用 Nagle 算法，降低延迟
                        using var tcpStream = tcpClient.GetStream();

                        Console.WriteLine("Session 0: FFmpeg 已通过 TCP 直连");
                        // 5. 直接从 TCP 流传输到 HTTP 响应（零中转）
                        await StreamFromSourceAsync(response, tcpStream, cancellationToken);
                    }
                }
                finally
                {
                    tcpListener?.Stop();

                    // 终止 FFmpeg 进程
                    if (ffmpegPid > 0)
                    {
                        try
                        {
                            var proc = Process.GetProcessById(ffmpegPid);
                            if (!proc.HasExited)
                            {
                                Console.WriteLine($"Killing FFmpeg (PID: {ffmpegPid})");
                                proc.Kill(entireProcessTree: true);
                            }
                        }
                        catch (Exception ex)
                        {
                            Console.WriteLine($"Error killing FFmpeg: {ex.Message}");
                        }
                    }
                }
            }
            else
            {
                // 正常模式：直接启动 FFmpeg 进程（stdout 输出）
                var ffmpegArgs = $"{ffmpegArgsBase} -";
                Console.WriteLine($"Starting FFmpeg {ffmpegArgs}");

                var processInfo = new ProcessStartInfo
                {
                    FileName = "ffmpeg",
                    Arguments = ffmpegArgs,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };

                Console.WriteLine("Starting FFmpeg process...");
                process = Process.Start(processInfo);
                if (process == null)
                {
                    Console.WriteLine("Failed to start FFmpeg process");
                    Response.StatusCode = 500;
                    await Response.WriteAsync("Failed to start video encoder", cancellationToken);
                    return;
                }

                Console.WriteLine($"FFmpeg process started (PID: {process.Id})");

                // Capture stderr for error messages
                var stderrBuilder = new StringBuilder();
                stderrTask = Task.Run(async () =>
                {
                    try
                    {
                        using var reader = process.StandardError;
                        string? line;
                        while ((line = await reader.ReadLineAsync(cancellationToken)) != null)
                        {
                            Console.WriteLine($"FFmpeg stderr: {line}");
                            stderrBuilder.AppendLine(line);
                        }
                    }
                    catch (OperationCanceledException) { }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"Error reading FFmpeg stderr: {ex.Message}");
                    }
                }, cancellationToken);

                var ffmpegStream = process.StandardOutput.BaseStream;

                // 流式传输（含首次读取超时检查）
                await StreamFromSourceWithValidationAsync(
                    response, ffmpegStream, process, stderrBuilder, stderrTask, cancellationToken);
            }
        }
        catch (OperationCanceledException)
        {
            Console.WriteLine("GetStream cancelled - client disconnected");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetStream error: {ex}");
            if (!Response.HasStarted)
            {
                try
                {
                    Response.StatusCode = 500;
                    await Response.WriteAsync($"Error: {ex.Message}", cancellationToken);
                }
                catch (Exception writeEx)
                {
                    Console.WriteLine($"Error writing error response: {writeEx.Message}");
                }
            }
        }
        finally
        {
            // 清理正常模式的资源（Session 0 的 FFmpeg 由 DesktopAgent 负责清理）
            if (process != null && !process.HasExited)
            {
                try
                {
                    Console.WriteLine($"Killing FFmpeg process (PID: {process.Id})");
                    process.Kill(entireProcessTree: true);
                    await process.WaitForExitAsync(new CancellationTokenSource(2000).Token);
                    Console.WriteLine("FFmpeg process killed");
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error killing FFmpeg: {ex.Message}");
                }
            }

            await WaitForStderrAsync(stderrTask);
            process?.Dispose();
        }
    }

    /// <summary>
    /// 从流源读取并推送到 HTTP 响应（Session 0 命名管道模式）
    /// </summary>
    private static async Task StreamFromSourceAsync(
        HttpResponse response, Stream source, CancellationToken ct)
    {
        const int bufferSize = 65536;
        var buffer = new byte[bufferSize];
        long totalBytes = 0;

        while (!ct.IsCancellationRequested)
        {
            var bytesRead = await source.ReadAsync(buffer.AsMemory(0, bufferSize), ct);
            if (bytesRead == 0)
            {
                Console.WriteLine("FFmpeg pipe stream ended");
                break;
            }

            await response.Body.WriteAsync(buffer.AsMemory(0, bytesRead), ct);
            await response.Body.FlushAsync(ct);
            totalBytes += bytesRead;
        }

        Console.WriteLine($"Stream ended. Total bytes sent: {totalBytes}");
    }

    /// <summary>
    /// 从 FFmpeg 进程标准输出读取并推送到 HTTP 响应（正常模式，含首次超时验证）
    /// </summary>
    private static async Task StreamFromSourceWithValidationAsync(
        HttpResponse response, Stream ffmpegStream, Process process,
        StringBuilder stderrBuilder, Task stderrTask, CancellationToken ct)
    {
        const int bufferSize = 65536;
        var buffer = new byte[bufferSize];
        long totalBytes = 0;
        var isFirstRead = true;

        while (!ct.IsCancellationRequested)
        {
            int bytesRead;

            if (isFirstRead)
            {
                using var timeoutCts = new CancellationTokenSource(3000);
                using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);

                try
                {
                    bytesRead = await ffmpegStream.ReadAsync(buffer.AsMemory(0, bufferSize), linkedCts.Token);
                }
                catch (OperationCanceledException) when (timeoutCts.IsCancellationRequested && !ct.IsCancellationRequested)
                {
                    Console.WriteLine("FFmpeg initial data timeout");
                    await WaitForStderrAsync(stderrTask);
                    Console.WriteLine($"Stderr: {stderrBuilder}");
                    response.StatusCode = 500;
                    await response.HttpContext.Response.WriteAsync("FFmpeg failed to produce output in time", ct);
                    return;
                }

                if (bytesRead == 0 || process.HasExited)
                {
                    await WaitForStderrAsync(stderrTask);
                    Console.WriteLine($"FFmpeg exited early with code: {process.ExitCode}. Stderr: {stderrBuilder}");
                    response.StatusCode = 500;
                    await response.HttpContext.Response.WriteAsync("FFmpeg failed to start capturing", ct);
                    return;
                }

                isFirstRead = false;
            }
            else
            {
                bytesRead = await ffmpegStream.ReadAsync(buffer.AsMemory(0, bufferSize), ct);
                if (bytesRead == 0)
                {
                    Console.WriteLine("FFmpeg stream ended");
                    if (process.HasExited)
                    {
                        Console.WriteLine($"FFmpeg exit code: {process.ExitCode}");
                    }
                    break;
                }
            }

            await response.Body.WriteAsync(buffer.AsMemory(0, bytesRead), ct);
            await response.Body.FlushAsync(ct);
            totalBytes += bytesRead;
        }

        Console.WriteLine($"Stream ended. Total bytes sent: {totalBytes}");
    }

    private static async Task<bool> CheckFfmpegAvailableAsync(CancellationToken cancellationToken)
    {
        try
        {
            var ffmpegCheck = new ProcessStartInfo
            {
                FileName = "ffmpeg",
                Arguments = "-version",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };

            using var checkProcess = Process.Start(ffmpegCheck);
            if (checkProcess == null)
            {
                Console.WriteLine("FFmpeg check failed: null process");
                return false;
            }

            // Read version output
            var versionOutput = await checkProcess.StandardOutput.ReadToEndAsync(cancellationToken);

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            cts.CancelAfter(5000);
            await checkProcess.WaitForExitAsync(cts.Token);

            if (checkProcess.ExitCode != 0)
            {
                Console.WriteLine($"FFmpeg check failed with exit code: {checkProcess.ExitCode}");
                return false;
            }

            // Parse and cache FFmpeg version
            var version = ParseFfmpegVersion(versionOutput);
            if (version != null)
            {
                lock (_cacheLock)
                {
                    _cachedFfmpegVersion = version;
                }
                Console.WriteLine($"FFmpeg version detected: {version}");
            }

            return true;
        }
        catch (Exception checkEx)
        {
            Console.WriteLine($"FFmpeg availability check error: {checkEx.Message}");
            return false;
        }
    }

    private static Version? ParseFfmpegVersion(string versionOutput)
    {
        try
        {
            // First try: n-prefixed git build format (n8.0.1-55-gxxxx-yyyymmdd)
            var nPrefixMatch = System.Text.RegularExpressions.Regex.Match(
                versionOutput,
                @"ffmpeg version n(\d+)\.(\d+)(?:\.(\d+))?-");

            if (nPrefixMatch.Success)
            {
                var major = int.Parse(nPrefixMatch.Groups[1].Value);
                var minor = int.Parse(nPrefixMatch.Groups[2].Value);
                var patch = nPrefixMatch.Groups[3].Success ? int.Parse(nPrefixMatch.Groups[3].Value) : 0;
                Console.WriteLine($"Detected FFmpeg git build version: {major}.{minor}.{patch}");
                return new Version(major, minor, patch);
            }

            // Second try: date-based git build format (2024-08-11-git-xxx)
            var dateMatch = System.Text.RegularExpressions.Regex.Match(
                versionOutput,
                @"ffmpeg version (\d{4})-(\d{2})-(\d{2})-git");

            if (dateMatch.Success)
            {
                var year = int.Parse(dateMatch.Groups[1].Value);
                Console.WriteLine($"Detected FFmpeg git build from {dateMatch.Groups[1].Value}-{dateMatch.Groups[2].Value}-{dateMatch.Groups[3].Value}");
                return year >= 2023 ? new Version(99, 0, 0) : new Version(5, 0, 0);
            }

            // Third try: standard version format (6.1.1, 7.0, etc.)
            var match = System.Text.RegularExpressions.Regex.Match(
                versionOutput,
                @"ffmpeg version (\d+)\.(\d+)(?:\.(\d+))?");

            if (match.Success)
            {
                var major = int.Parse(match.Groups[1].Value);
                var minor = int.Parse(match.Groups[2].Value);
                var patch = match.Groups[3].Success ? int.Parse(match.Groups[3].Value) : 0;
                return new Version(major, minor, patch);
            }

            // Fourth try: version with suffix like "7.1-full_build-www.gyan.dev"
            match = System.Text.RegularExpressions.Regex.Match(
                versionOutput,
                @"ffmpeg version (\d+)\.(\d+)(?:\.(\d+))?[-_]");

            if (match.Success)
            {
                var major = int.Parse(match.Groups[1].Value);
                var minor = int.Parse(match.Groups[2].Value);
                var patch = match.Groups[3].Success ? int.Parse(match.Groups[3].Value) : 0;
                return new Version(major, minor, patch);
            }

            // Fifth try: N-xxxxx git build format
            if (versionOutput.Contains("ffmpeg version N-") || versionOutput.Contains("ffmpeg version git-"))
            {
                Console.WriteLine("Detected FFmpeg git/nightly build, assuming latest version");
                return new Version(99, 0, 0);
            }

            var firstLine = versionOutput.Split('\n').FirstOrDefault()?.Trim();
            Console.WriteLine($"Could not parse FFmpeg version from: {firstLine}");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Error parsing FFmpeg version: {ex.Message}");
        }

        return null;
    }

    /// <summary>
    /// 根据编码器类型选择最佳的屏幕截取方式。
    /// 硬件编码器 + ddagrab = 全 GPU 管线（d3d11 帧直送编码器，零 CPU 开销）；
    /// 软件编码器 = gdigrab（CPU 路径，避免 hwdownload GPU→CPU 转移开销）。
    /// </summary>
    private static async Task<(string input, string method)> GetScreenCaptureInputAsync(
        string encoder, CancellationToken cancellationToken)
    {
        lock (_cacheLock)
        {
            if (_cachedScreenCaptureInput != null)
            {
                var method = _cachedScreenCaptureInput.Contains("ddagrab") ? "ddagrab" : "gdigrab";
                return (_cachedScreenCaptureInput, method);
            }
        }

        string screenCaptureInput;
        string captureMethod;

        var isHwEncoder = encoder != "libx264";

        // 只有硬件编码器才尝试 ddagrab：
        // - ddagrab 产出 d3d11 GPU 帧，硬件编码器可直接消费（零 CPU 开销）
        // - 软件编码器（libx264）需要 CPU 帧，ddagrab 的 hwdownload 开销反而更大
        // - ddagrab 使用 DXGI Desktop Duplication API，不受 DWM 刷新优化影响
        if (isHwEncoder && await TestDdagrabPipelineAsync(encoder, cancellationToken))
        {
            screenCaptureInput = $"-f lavfi -i \"ddagrab=framerate={Framerate}:draw_mouse=1\"";
            captureMethod = "ddagrab";
            Console.WriteLine($"Using ddagrab (DXGI) + {encoder} — 全 GPU 管线，零 CPU 开销，{Framerate}fps");
        }
        else
        {
            screenCaptureInput = $"-f gdigrab -framerate {Framerate} -rtbufsize 100M -thread_queue_size 512 -draw_mouse 1 -i desktop";
            captureMethod = "gdigrab";

            if (isHwEncoder)
                Console.WriteLine($"ddagrab + {encoder} 运行时测试失败，回退到 gdigrab");
            else
                Console.WriteLine("Using gdigrab (software encoder path)");
        }

        lock (_cacheLock)
        {
            _cachedScreenCaptureInput = screenCaptureInput;
        }

        return (screenCaptureInput, captureMethod);
    }

    /// <summary>
    /// 运行时测试 ddagrab + 硬件编码器的完整 GPU 管线是否可用。
    /// 实际捕获 10 帧并编码，比单纯检查 filter 存在更可靠。
    /// </summary>
    private static async Task<bool> TestDdagrabPipelineAsync(string encoder, CancellationToken cancellationToken)
    {
        try
        {
            Console.WriteLine($"Testing ddagrab + {encoder} pipeline (capturing 10 frames)...");

            var psi = new ProcessStartInfo
            {
                FileName = "ffmpeg",
                Arguments = $"-hide_banner -f lavfi -i \"ddagrab=framerate={Framerate}\" -c:v {encoder} -frames:v 10 -f null -",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };

            using var proc = Process.Start(psi);
            if (proc == null) return false;

            // 读取 stderr 避免缓冲区阻塞
            var stderrTask = proc.StandardError.ReadToEndAsync(cancellationToken);

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            cts.CancelAfter(15000); // 15 秒超时
            await proc.WaitForExitAsync(cts.Token);

            var stderr = await stderrTask;
            var success = proc.ExitCode == 0;

            if (success)
                Console.WriteLine($"ddagrab + {encoder} pipeline test PASSED");
            else
                Console.WriteLine($"ddagrab + {encoder} pipeline test FAILED (exit {proc.ExitCode}): {stderr.Trim()}");

            return success;
        }
        catch (OperationCanceledException)
        {
            Console.WriteLine($"ddagrab + {encoder} pipeline test timed out");
            return false;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ddagrab + {encoder} pipeline test error: {ex.Message}");
            return false;
        }
    }

    private static async Task<(string encoder, string encoderParams)> GetEncoderAsync(string crf, CancellationToken cancellationToken)
    {
        lock (_cacheLock)
        {
            if (_cachedEncoder != null && _cachedEncoderParams != null)
            {
                var updatedParams = UpdateCrfInParams(_cachedEncoder, _cachedEncoderParams, crf);
                return (_cachedEncoder, updatedParams);
            }
        }

        string encoder = "libx264";
        string encoderParams = $"-preset ultrafast -tune zerolatency -crf {crf} -bf 0";

        var hwEncoders = new[]
        {
            ("h264_nvenc", "-preset p1 -tune ull -rc cbr -delay 0 -zerolatency 1 -forced-idr 1 -strict_gop 1 -rc-lookahead 0 -surfaces 1 -bf 0"),
            ("h264_qsv", "-preset veryfast -global_quality {0} -bf 0 -look_ahead 0 -async_depth 1"),
            ("h264_amf", "-quality speed -rc cqp -qp_i {0} -qp_p {0} -bf 0 -preanalysis 0")
        };

        foreach (var (hwEncoder, hwParamsTemplate) in hwEncoders)
        {
            if (await TestEncoderAsync(hwEncoder, cancellationToken))
            {
                encoder = hwEncoder;
                encoderParams = string.Format(hwParamsTemplate, crf);
                Console.WriteLine($"Hardware encoder verified working: {hwEncoder}");
                break;
            }
        }

        if (encoder == "libx264")
        {
            Console.WriteLine("No hardware encoder available, using libx264 (CPU)");
        }

        lock (_cacheLock)
        {
            _cachedEncoder = encoder;
            _cachedEncoderParams = encoderParams;
        }

        return (encoder, encoderParams);
    }

    private static async Task<bool> TestEncoderAsync(string encoderName, CancellationToken cancellationToken)
    {
        try
        {
            var testArgs = $"-hide_banner -f lavfi -i color=c=black:s=256x256:d=0.1 -frames:v 1 -c:v {encoderName} -f null -";

            var testProcess = new ProcessStartInfo
            {
                FileName = "ffmpeg",
                Arguments = testArgs,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };

            using var proc = Process.Start(testProcess);
            if (proc == null) return false;

            var stderrTask = proc.StandardError.ReadToEndAsync(cancellationToken);

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            cts.CancelAfter(10000);

            await proc.WaitForExitAsync(cts.Token);
            var stderr = await stderrTask;

            if (proc.ExitCode == 0)
            {
                Console.WriteLine($"Encoder {encoderName} test passed");
                return true;
            }

            var errorLines = stderr.Split('\n')
                .Where(line => !string.IsNullOrWhiteSpace(line))
                .Where(line => !line.TrimStart().StartsWith("Input #"))
                .Where(line => !line.TrimStart().StartsWith("Output #"))
                .Where(line => !line.TrimStart().StartsWith("Stream #"))
                .Where(line => !line.TrimStart().StartsWith("Duration:"))
                .Where(line => !line.Contains("Stream mapping:"))
                .Where(line => !line.Contains("Press [q]"))
                .Select(line => line.Trim())
                .ToList();

            var errorMessage = errorLines.FirstOrDefault() ?? "Unknown error";
            Console.WriteLine($"Encoder {encoderName} test failed: {errorMessage}");

            return false;
        }
        catch (OperationCanceledException)
        {
            Console.WriteLine($"Encoder {encoderName} test timed out");
            return false;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Error testing encoder {encoderName}: {ex.Message}");
            return false;
        }
    }

    private static string UpdateCrfInParams(string encoder, string cachedParams, string newCrf)
    {
        return encoder switch
        {
            "h264_nvenc" => "-preset p1 -tune ull -rc cbr -delay 0 -zerolatency 1 -forced-idr 1 -strict_gop 1 -rc-lookahead 0 -surfaces 1 -bf 0",
            "h264_qsv" => $"-preset veryfast -global_quality {newCrf} -bf 0 -look_ahead 0 -async_depth 1",
            "h264_amf" => $"-quality speed -rc cqp -qp_i {newCrf} -qp_p {newCrf} -bf 0 -preanalysis 0",
            _ => $"-preset ultrafast -tune zerolatency -crf {newCrf} -bf 0"
        };
    }

    private static int ParseBitrateToKbps(string bitrate)
    {
        if (bitrate.EndsWith("M", StringComparison.OrdinalIgnoreCase))
        {
            if (int.TryParse(bitrate[..^1], out var mbps))
                return mbps * 1000;
        }
        else if (bitrate.EndsWith("k", StringComparison.OrdinalIgnoreCase))
        {
            if (int.TryParse(bitrate[..^1], out var kbps))
                return kbps;
        }

        return 3000;
    }

    private static async Task WaitForStderrAsync(Task? stderrTask)
    {
        if (stderrTask != null)
        {
            try
            {
                await stderrTask.WaitAsync(TimeSpan.FromSeconds(2));
            }
            catch { }
        }
    }
}
