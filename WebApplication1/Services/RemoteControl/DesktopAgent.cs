using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

/// <summary>
/// 桌面交互代理：运行在交互式用户会话中，通过命名管道与 IIS 进程通信，
/// 代理执行需要桌面访问的操作（截图、鼠标、键盘、锁屏等）。
/// </summary>
public static class DesktopAgent
{
    /// <summary>
    /// 控制命令管道名称
    /// </summary>
    public const string PipeName = "ChuckieHelper_DesktopAgent";

    /// <summary>
    /// 代理协议版本号（每次修改代理协议时递增，用于检测旧版本代理）
    /// </summary>
    public const int ProtocolVersion = 2;

    /// <summary>
    /// 代理空闲超时时间（无连接时自动退出）
    /// </summary>
    private static readonly TimeSpan IdleTimeout = ResolveIdleTimeout();

    private const int DefaultIdleTimeoutMinutes = 5;
    private const string IdleTimeoutEnvName = "CHUCKIEHELPER_AGENT_IDLE_TIMEOUT_MINUTES";

    private static readonly SemaphoreSlim _launchLock = new(1, 1);
    private static bool _agentLaunchAttempted;

    private static void Log(string message)
        => AgentStartupLogger.Log("DesktopAgent", message);

    private static void LogException(string message, Exception ex)
        => AgentStartupLogger.LogException("DesktopAgent", message, ex);

    private static TimeSpan ResolveIdleTimeout()
    {
        var raw = Environment.GetEnvironmentVariable(IdleTimeoutEnvName);
        if (string.IsNullOrWhiteSpace(raw))
            return TimeSpan.FromMinutes(DefaultIdleTimeoutMinutes);

        if (!int.TryParse(raw, out var minutes))
        {
            AgentStartupLogger.Log("DesktopAgent", $"环境变量 {IdleTimeoutEnvName} 值无效: '{raw}'，使用默认 {DefaultIdleTimeoutMinutes} 分钟");
            return TimeSpan.FromMinutes(DefaultIdleTimeoutMinutes);
        }

        if (minutes <= 0)
            return Timeout.InfiniteTimeSpan;

        if (minutes > 24 * 60)
        {
            AgentStartupLogger.Log("DesktopAgent", $"环境变量 {IdleTimeoutEnvName} 值过大: {minutes}，使用默认 {DefaultIdleTimeoutMinutes} 分钟");
            return TimeSpan.FromMinutes(DefaultIdleTimeoutMinutes);
        }

        return TimeSpan.FromMinutes(minutes);
    }

    #region 服务端（在交互式会话中运行）

    /// <summary>
    /// 启动桌面代理服务器（在 --desktop-agent 模式下调用）
    /// </summary>
    public static async Task RunServerAsync(CancellationToken ct)
    {
        var idleTimeoutText = IdleTimeout == Timeout.InfiniteTimeSpan
            ? "disabled"
            : $"{IdleTimeout.TotalMinutes:0} min";
        Log($"桌面代理服务器启动中，协议版本={ProtocolVersion}，日志文件={AgentStartupLogger.LogFilePath}，idleTimeout={idleTimeoutText}");
        var systemService = new SystemService();
        var lastActivity = DateTime.UtcNow;

        // 空闲超时检查任务
        if (IdleTimeout != Timeout.InfiniteTimeSpan)
        {
            _ = Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    await Task.Delay(TimeSpan.FromMinutes(1), ct);
                    if (DateTime.UtcNow - lastActivity > IdleTimeout)
                    {
                        Log("空闲超时，代理即将退出");
                        Environment.Exit(0);
                    }
                }
            }, ct);
        }
        else
        {
            Log($"已禁用空闲自动退出（{IdleTimeoutEnvName} <= 0）");
        }

        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var pipeServer = new NamedPipeServerStream(
                    PipeName,
                    PipeDirection.InOut,
                    NamedPipeServerStream.MaxAllowedServerInstances,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous);

                Log($"等待客户端连接，管道名={PipeName}");
                await pipeServer.WaitForConnectionAsync(ct);
                lastActivity = DateTime.UtcNow;
                Log("客户端已连接");

                // 每个连接在当前循环中同步处理（简单可靠）
                // 对于并发请求，多个管道实例会同时接受连接
                try
                {
                    await HandleConnectionAsync(pipeServer, systemService, ct);
                }
                catch (Exception ex)
                {
                    LogException("处理连接时出错", ex);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                LogException("管道服务器错误", ex);
                await Task.Delay(1000, ct);
            }
        }

        Log("桌面代理服务器已停止");
    }

    /// <summary>
    /// 处理单个客户端连接
    /// </summary>
    private static async Task HandleConnectionAsync(
        NamedPipeServerStream pipe, SystemService systemService, CancellationToken ct)
    {
        var requestBytes = await ReadMessageAsync(pipe, ct);
        if (requestBytes == null || requestBytes.Length == 0) return;

        var requestJson = Encoding.UTF8.GetString(requestBytes);
        Log($"收到命令: {requestJson}");

        using var doc = JsonDocument.Parse(requestJson);
        var root = doc.RootElement;
        var type = root.GetProperty("type").GetString() ?? "";

        // FFmpeg 流式命令需要特殊处理（长连接，不走请求-响应模式）
        if (type == "start_ffmpeg")
        {
            await HandleStartFFmpegAsync(pipe, root, ct);
            return;
        }

        byte[] response;

        try
        {
            response = type switch
            {
                "screenshot" => HandleScreenshot(systemService),
                "mouse_click" => HandleMouseClick(root, systemService),
                "mouse_right_click" => HandleMouseRightClick(root, systemService),
                "mouse_middle_click" => HandleMouseMiddleClick(root, systemService),
                "mouse_down" => HandleMouseDown(root, systemService),
                "mouse_up" => HandleMouseUp(root, systemService),
                "mouse_move" => HandleMouseMove(root, systemService),
                "mouse_wheel" => HandleMouseWheel(root, systemService),
                "keyboard" => HandleKeyboard(root, systemService),
                "keyboard_multi" => HandleKeyboardMulti(root, systemService),
                "lock" => HandleLock(systemService),
                "screen_bounds" => HandleScreenBounds(systemService),
                "launch_ffmpeg" => HandleLaunchFFmpeg(root),
                "enum_windows" => HandleEnumWindows(),
                "clipboard_get" => HandleClipboardGet(systemService),
                "clipboard_set" => HandleClipboardSet(root, systemService),
                "ping" => Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { ok = true, version = ProtocolVersion })),
                _ => ErrorResponse($"未知命令类型: {type}")
            };
        }
        catch (Exception ex)
        {
            LogException($"处理命令 '{type}' 时出错", ex);
            response = ErrorResponse(ex.Message);
        }

        await WriteMessageAsync(pipe, response, ct);
    }

    #region 命令处理器

    private static byte[] HandleScreenshot(SystemService svc)
    {
        var imageBytes = svc.CaptureScreen();
        if (imageBytes.Length == 0)
            return ErrorResponse("截图失败");

        // 截图直接返回原始 JPEG 字节，前缀 "IMG:" 标记
        var prefix = Encoding.UTF8.GetBytes("IMG:");
        var result = new byte[prefix.Length + imageBytes.Length];
        Buffer.BlockCopy(prefix, 0, result, 0, prefix.Length);
        Buffer.BlockCopy(imageBytes, 0, result, prefix.Length, imageBytes.Length);
        return result;
    }

    private static byte[] HandleMouseClick(JsonElement root, SystemService svc)
    {
        svc.SendMouseClick(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble());
        return OkResponse();
    }

    private static byte[] HandleMouseRightClick(JsonElement root, SystemService svc)
    {
        svc.SendMouseRightClick(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble());
        return OkResponse();
    }

    private static byte[] HandleMouseMiddleClick(JsonElement root, SystemService svc)
    {
        svc.SendMouseMiddleClick(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble());
        return OkResponse();
    }

    private static byte[] HandleMouseDown(JsonElement root, SystemService svc)
    {
        var button = root.TryGetProperty("button", out var b) ? b.GetInt32() : 0;
        svc.SendMouseDown(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble(), button);
        return OkResponse();
    }

    private static byte[] HandleMouseUp(JsonElement root, SystemService svc)
    {
        var button = root.TryGetProperty("button", out var b) ? b.GetInt32() : 0;
        svc.SendMouseUp(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble(), button);
        return OkResponse();
    }

    private static byte[] HandleMouseMove(JsonElement root, SystemService svc)
    {
        svc.SendMouseMove(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble());
        return OkResponse();
    }

    private static byte[] HandleMouseWheel(JsonElement root, SystemService svc)
    {
        var delta = root.TryGetProperty("delta", out var d) ? d.GetInt32() : 120;
        svc.SendMouseWheel(root.GetProperty("x").GetDouble(), root.GetProperty("y").GetDouble(), delta);
        return OkResponse();
    }

    private static byte[] HandleKeyboard(JsonElement root, SystemService svc)
    {
        svc.SendKeyboardEvent(
            (byte)root.GetProperty("vkCode").GetInt32(),
            root.GetProperty("isKeyDown").GetBoolean());
        return OkResponse();
    }

    private static byte[] HandleKeyboardMulti(JsonElement root, SystemService svc)
    {
        var vkCodes = root.GetProperty("vkCodes").EnumerateArray()
            .Select(v => (byte)v.GetInt32()).ToArray();
        svc.SendKeyboardEvents(vkCodes, root.GetProperty("isKeyDown").GetBoolean());
        return OkResponse();
    }

    private static byte[] HandleLock(SystemService svc)
    {
        svc.LockWorkstation();
        return OkResponse();
    }

    private static byte[] HandleClipboardGet(SystemService svc)
    {
        var text = svc.GetClipboardTextDirect();
        return Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { ok = true, text }));
    }

    private static byte[] HandleClipboardSet(JsonElement root, SystemService svc)
    {
        var text = root.GetProperty("text").GetString() ?? "";
        svc.SetClipboardTextDirect(text);
        return OkResponse();
    }

    private static byte[] HandleScreenBounds(SystemService svc)
    {
        // 通过反射调用 GetVirtualScreenBounds（它是 private 方法）
        var method = typeof(SystemService).GetMethod("GetVirtualScreenBounds",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance);
        if (method == null)
            return ErrorResponse("无法获取屏幕边界");

        var bounds = (System.Drawing.Rectangle)method.Invoke(svc, null)!;
        var json = JsonSerializer.Serialize(new
        {
            ok = true,
            x = bounds.X,
            y = bounds.Y,
            width = bounds.Width,
            height = bounds.Height
        });
        return Encoding.UTF8.GetBytes(json);
    }

    /// <summary>
    /// 启动 FFmpeg 进程并返回 PID（不代理数据，FFmpeg 通过 TCP 直连 IIS）。
    /// 用于低延迟视频流场景。
    /// </summary>
    private static byte[] HandleLaunchFFmpeg(JsonElement root)
    {
        var args = root.GetProperty("args").GetString() ?? "";
        var ffmpegPath = FindFFmpegPath();
        if (ffmpegPath == null)
            return ErrorResponse("FFmpeg 未找到。请确保 ffmpeg 已安装并在系统 PATH 中。");

        Console.WriteLine($"[DesktopAgent] launch_ffmpeg: {ffmpegPath} {args}");

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = ffmpegPath,
                Arguments = args,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true
                // 不重定向 stdout — FFmpeg 通过 TCP 输出视频数据
            };

            var proc = Process.Start(psi);
            if (proc == null)
                return ErrorResponse("FFmpeg 进程启动返回 null");

            Console.WriteLine($"[DesktopAgent] FFmpeg 已启动 (PID: {proc.Id})");

            // 后台读取 stderr 用于调试
            _ = Task.Run(async () =>
            {
                try
                {
                    using var reader = proc.StandardError;
                    while (await reader.ReadLineAsync() is { } line)
                        Console.WriteLine($"[DesktopAgent FFmpeg:{proc.Id}] {line}");
                }
                catch { /* 忽略 */ }
            });

            return Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { ok = true, pid = proc.Id }));
        }
        catch (Exception ex)
        {
            return ErrorResponse($"FFmpeg 启动异常: {ex.Message}");
        }
    }

    /// <summary>
    /// 处理 FFmpeg 流式传输命令：在交互式会话中启动 FFmpeg，将其 stdout 代理到管道（旧方案，延迟较高）
    /// </summary>
    private static async Task HandleStartFFmpegAsync(
        NamedPipeServerStream pipe, JsonElement root, CancellationToken ct)
    {
        var ffmpegArgs = root.GetProperty("args").GetString() ?? "";
        Console.WriteLine($"[DesktopAgent] 启动 FFmpeg 流: ffmpeg {ffmpegArgs}");

        Process? ffmpegProcess = null;
        var okSent = false; // 追踪是否已发送 OK，用于决定异常时是否发送错误响应

        try
        {
            // 先检查 ffmpeg 是否可用
            var ffmpegPath = FindFFmpegPath();
            if (ffmpegPath == null)
            {
                var errMsg = "FFmpeg 未找到。请确保 ffmpeg 已安装并在系统 PATH 中。";
                Console.WriteLine($"[DesktopAgent] {errMsg}");
                await WriteMessageAsync(pipe, ErrorResponse(errMsg), ct);
                return;
            }

            Console.WriteLine($"[DesktopAgent] 使用 FFmpeg: {ffmpegPath}");

            var psi = new ProcessStartInfo
            {
                FileName = ffmpegPath,
                Arguments = ffmpegArgs,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };

            ffmpegProcess = Process.Start(psi);
            if (ffmpegProcess == null)
            {
                var errMsg = "FFmpeg 进程启动返回 null";
                Console.WriteLine($"[DesktopAgent] {errMsg}");
                await WriteMessageAsync(pipe, ErrorResponse(errMsg), ct);
                return;
            }

            Console.WriteLine($"[DesktopAgent] FFmpeg 已启动 (PID: {ffmpegProcess.Id})");

            // 后台读取 stderr 用于调试
            var stderrBuilder = new StringBuilder();
            _ = Task.Run(async () =>
            {
                try
                {
                    using var reader = ffmpegProcess.StandardError;
                    while (await reader.ReadLineAsync(ct) is { } line)
                    {
                        Console.WriteLine($"[DesktopAgent FFmpeg stderr] {line}");
                        if (stderrBuilder.Length < 4096) // 限制长度
                            stderrBuilder.AppendLine(line);
                    }
                }
                catch { /* 忽略 */ }
            }, ct);

            // 等待一小段时间检查 FFmpeg 是否立即退出（例如参数错误）
            await Task.Delay(500, ct);
            if (ffmpegProcess.HasExited)
            {
                var errMsg = $"FFmpeg 启动后立即退出 (ExitCode: {ffmpegProcess.ExitCode})。" +
                    $"Stderr: {stderrBuilder.ToString().Trim()}";
                Console.WriteLine($"[DesktopAgent] {errMsg}");
                await WriteMessageAsync(pipe, ErrorResponse(errMsg), ct);
                return;
            }

            // 先发送 OK 响应（带帧头），之后切换到原始字节流模式
            await WriteMessageAsync(pipe, OkResponse(), ct);
            okSent = true;

            // 将 FFmpeg stdout 持续转发到管道
            var buffer = new byte[65536];
            using var stdout = ffmpegProcess.StandardOutput.BaseStream;

            while (!ct.IsCancellationRequested)
            {
                int bytesRead;
                try
                {
                    bytesRead = await stdout.ReadAsync(buffer, ct);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                if (bytesRead == 0)
                {
                    Console.WriteLine("[DesktopAgent] FFmpeg stdout 已结束");
                    break;
                }

                try
                {
                    await pipe.WriteAsync(buffer.AsMemory(0, bytesRead), ct);
                    await pipe.FlushAsync(ct);
                }
                catch (IOException)
                {
                    // 管道断开 = 客户端（IIS）断开连接
                    Console.WriteLine("[DesktopAgent] 管道断开，客户端已断开");
                    break;
                }
            }
        }
        catch (OperationCanceledException)
        {
            Console.WriteLine("[DesktopAgent] FFmpeg 流已取消");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[DesktopAgent] FFmpeg 流错误: {ex.Message}");
            // 如果还没发送 OK，则发送错误响应给客户端
            if (!okSent)
            {
                try
                {
                    await WriteMessageAsync(pipe, ErrorResponse($"FFmpeg 流异常: {ex.Message}"), ct);
                }
                catch { /* 管道可能已断开 */ }
            }
        }
        finally
        {
            if (ffmpegProcess != null && !ffmpegProcess.HasExited)
            {
                try
                {
                    Console.WriteLine($"[DesktopAgent] 终止 FFmpeg 进程 (PID: {ffmpegProcess.Id})");
                    ffmpegProcess.Kill(entireProcessTree: true);
                    ffmpegProcess.WaitForExit(3000);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[DesktopAgent] 终止 FFmpeg 失败: {ex.Message}");
                }
            }

            ffmpegProcess?.Dispose();
            Console.WriteLine("[DesktopAgent] FFmpeg 流会话结束");
        }
    }

    /// <summary>
    /// 查找 FFmpeg 可执行文件路径
    /// </summary>
    private static string? FindFFmpegPath()
    {
        // 常见安装位置
        var candidates = new[]
        {
            "ffmpeg", // PATH 中
            @"C:\ffmpeg\bin\ffmpeg.exe",
            @"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"ffmpeg\bin\ffmpeg.exe"),
        };

        foreach (var candidate in candidates)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = candidate,
                    Arguments = "-version",
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true
                };
                using var proc = Process.Start(psi);
                if (proc != null)
                {
                    proc.WaitForExit(5000);
                    if (proc.ExitCode == 0)
                        return candidate;
                }
            }
            catch
            {
                // 此路径不可用，尝试下一个
            }
        }

        return null;
    }

    /// <summary>
    /// 枚举当前桌面上所有可见的顶层窗口，返回窗口 PID 和标题
    /// </summary>
    private static byte[] HandleEnumWindows()
    {
        var windows = new List<WindowInfoDto>();
        var sb = new StringBuilder(512);

        EnumWindows((hWnd, _) =>
        {
            if (!IsWindowVisible(hWnd)) return true;

            var length = GetWindowTextLength(hWnd);
            if (length == 0) return true;

            sb.Clear();
            sb.EnsureCapacity(length + 1);
            GetWindowText(hWnd, sb, length + 1);
            var title = sb.ToString();
            if (string.IsNullOrWhiteSpace(title)) return true;

            GetWindowThreadProcessId(hWnd, out var pid);
            if (pid == 0) return true;

            windows.Add(new WindowInfoDto { Pid = pid, Title = title });
            return true;
        }, IntPtr.Zero);

        // 使用 camelCase 序列化，确保客户端 GetProperty("pid") / GetProperty("title") 能正确匹配
        var json = JsonSerializer.Serialize(new { ok = true, windows }, _camelCaseOptions);
        return Encoding.UTF8.GetBytes(json);
    }

    private static readonly JsonSerializerOptions _camelCaseOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    #region Win32 P/Invoke

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int GetWindowTextLength(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out int lpdwProcessId);

    #endregion

    /// <summary>
    /// 窗口信息 DTO（用于 enum_windows 命令的序列化）
    /// </summary>
    private class WindowInfoDto
    {
        public int Pid { get; set; }
        public string Title { get; set; } = "";
    }

    private static byte[] OkResponse()
        => Encoding.UTF8.GetBytes("{\"ok\":true}");

    private static byte[] ErrorResponse(string message)
        => Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { ok = false, error = message }));

    #endregion

    #endregion

    #region 客户端（在 IIS 进程中调用）

    /// <summary>
    /// 确保桌面代理进程正在运行，如不运行则尝试启动
    /// </summary>
    public static async Task EnsureAgentRunningAsync()
    {
        Log("EnsureAgentRunningAsync 开始执行");
        // 先尝试 ping
        if (await TryPingAgentAsync())
        {
            Log("代理已在线，无需启动");
            return;
        }

        await _launchLock.WaitAsync();
        try
        {
            // 再次检查（可能其他线程已启动）
            if (await TryPingAgentAsync())
            {
                Log("代理在加锁后已在线（可能被其他线程启动）");
                return;
            }

            if (_agentLaunchAttempted)
            {
                Log("已尝试过启动代理但仍不可用，重置启动尝试标记后重试");
                // 重置标志，允许再次尝试
                _agentLaunchAttempted = false;
            }

            Log("代理未运行，准备启动交互式会话进程");

            var dllPath = InteractiveProcessLauncher.GetApplicationDllPath();
            var dotnetPath = InteractiveProcessLauncher.GetDotnetPath();
            var commandLine = $"\"{dotnetPath}\" \"{dllPath}\" --desktop-agent";
            Log($"启动命令: {commandLine}");

            var (success, pid) = InteractiveProcessLauncher.LaunchInInteractiveSession(commandLine, useElevatedTokenIfAvailable: true);
            _agentLaunchAttempted = true;

            if (!success)
            {
                Log("启动代理失败。请查看 InteractiveProcessLauncher 的错误日志（Win32 错误码与文本）并确认 IIS 应用程序池身份为 LocalSystem。");
                return;
            }

            Log($"代理进程已启动 (PID: {pid})，等待就绪...");

            // 等待代理就绪（最多 10 秒）
            for (int i = 0; i < 20; i++)
            {
                await Task.Delay(500);
                if (await TryPingAgentAsync())
                {
                    Log("代理已就绪");
                    return;
                }
            }

            Log("代理启动超时（启动后 10 秒内未通过 ping）");
            try
            {
                using var proc = Process.GetProcessById(pid);
                if (proc.HasExited)
                {
                    Log($"代理进程已退出，PID={pid}, ExitCode={proc.ExitCode}");
                }
                else
                {
                    Log($"代理进程仍在运行，PID={pid}, SessionId={proc.SessionId}, StartTime={proc.StartTime:yyyy-MM-dd HH:mm:ss}");
                }
            }
            catch (Exception ex)
            {
                LogException($"启动超时后检查代理进程状态失败，PID={pid}", ex);
            }
        }
        finally
        {
            Log("EnsureAgentRunningAsync 结束，释放启动锁");
            _launchLock.Release();
        }
    }

    /// <summary>
    /// 尝试 ping 代理
    /// </summary>
    private static async Task<bool> TryPingAgentAsync()
    {
        try
        {
            var response = await SendCommandInternalAsync(
                "{\"type\":\"ping\"}", TimeSpan.FromSeconds(2));
            var ok = response != null;
            if (!ok)
                Log("TryPingAgentAsync 失败：代理无响应");
            return ok;
        }
        catch (Exception ex)
        {
            LogException("TryPingAgentAsync 异常", ex);
            return false;
        }
    }

    /// <summary>
    /// 通过代理截取屏幕
    /// </summary>
    public static async Task<byte[]> CaptureScreenAsync(CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        var response = await SendCommandInternalAsync("{\"type\":\"screenshot\"}", TimeSpan.FromSeconds(10));
        if (response == null) return Array.Empty<byte>();

        // 检查是否是图片响应（以 "IMG:" 开头）
        if (response.Length > 4 &&
            response[0] == (byte)'I' && response[1] == (byte)'M' &&
            response[2] == (byte)'G' && response[3] == (byte)':')
        {
            var imageBytes = new byte[response.Length - 4];
            Buffer.BlockCopy(response, 4, imageBytes, 0, imageBytes.Length);
            return imageBytes;
        }

        // 否则是 JSON 错误响应
        var json = Encoding.UTF8.GetString(response);
        Console.WriteLine($"[DesktopAgent] 截图失败: {json}");
        return Array.Empty<byte>();
    }

    /// <summary>
    /// 通过代理发送鼠标点击
    /// </summary>
    public static async Task SendMouseClickAsync(double x, double y, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_click", x, y }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送鼠标右键点击
    /// </summary>
    public static async Task SendMouseRightClickAsync(double x, double y, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_right_click", x, y }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送鼠标中键点击
    /// </summary>
    public static async Task SendMouseMiddleClickAsync(double x, double y, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_middle_click", x, y }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送鼠标按下
    /// </summary>
    public static async Task SendMouseDownAsync(double x, double y, int button = 0, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_down", x, y, button }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送鼠标释放
    /// </summary>
    public static async Task SendMouseUpAsync(double x, double y, int button = 0, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_up", x, y, button }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送鼠标移动
    /// </summary>
    public static async Task SendMouseMoveAsync(double x, double y, CancellationToken ct = default)
    {
        // 鼠标移动不需要每次都检查代理是否运行
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_move", x, y }),
            TimeSpan.FromSeconds(2));
    }

    /// <summary>
    /// 通过代理发送鼠标滚轮
    /// </summary>
    public static async Task SendMouseWheelAsync(double x, double y, int delta, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "mouse_wheel", x, y, delta }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送键盘事件
    /// </summary>
    public static async Task SendKeyboardEventAsync(byte vkCode, bool isKeyDown, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "keyboard", vkCode = (int)vkCode, isKeyDown }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理发送多键键盘事件
    /// </summary>
    public static async Task SendKeyboardEventsAsync(byte[] vkCodes, bool isKeyDown, CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "keyboard_multi", vkCodes = vkCodes.Select(v => (int)v).ToArray(), isKeyDown }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理锁定工作站
    /// </summary>
    public static async Task LockWorkstationAsync(CancellationToken ct = default)
    {
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync("{\"type\":\"lock\"}", TimeSpan.FromSeconds(5));
    }

    /// <summary>
    /// 通过代理获取远程剪贴板文本
    /// </summary>
    public static async Task<string> GetClipboardTextAsync(CancellationToken ct = default)
    {
        try
        {
            await EnsureAgentRunningAsync();
            var response = await SendCommandInternalAsync("{\"type\":\"clipboard_get\"}", TimeSpan.FromSeconds(3));
            if (response == null || response.Length == 0) return "";

            var json = Encoding.UTF8.GetString(response);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.TryGetProperty("text", out var textEl))
                return textEl.GetString() ?? "";
            return "";
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[DesktopAgent] GetClipboardText error: {ex.Message}");
            return "";
        }
    }

    /// <summary>
    /// 通过代理设置远程剪贴板文本
    /// </summary>
    public static async Task SetClipboardTextAsync(string text, CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(text)) return;
        await EnsureAgentRunningAsync();
        await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "clipboard_set", text }),
            TimeSpan.FromSeconds(3));
    }

    /// <summary>
    /// 通过代理枚举交互式桌面上的可见窗口，返回 PID 到窗口标题的映射
    /// </summary>
    public static async Task<Dictionary<int, string>> EnumWindowsAsync(CancellationToken ct = default)
    {
        var result = new Dictionary<int, string>();
        try
        {
            await EnsureAgentRunningAsync();
            var response = await SendCommandInternalAsync("{\"type\":\"enum_windows\"}", TimeSpan.FromSeconds(5));
            if (response == null) return result;

            var json = Encoding.UTF8.GetString(response);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            if (!root.TryGetProperty("ok", out var okProp) || !okProp.GetBoolean())
                return result;

            if (root.TryGetProperty("windows", out var windowsArr))
            {
                foreach (var win in windowsArr.EnumerateArray())
                {
                    var pid = win.GetProperty("pid").GetInt32();
                    var title = win.GetProperty("title").GetString() ?? "";
                    // 同一个进程可能有多个窗口，保留最长标题（通常是主窗口）
                    if (!result.TryGetValue(pid, out var existing) || title.Length > existing.Length)
                    {
                        result[pid] = title;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[DesktopAgent] EnumWindows 失败: {ex.Message}");
        }
        return result;
    }

    /// <summary>
    /// 在交互式会话中启动 FFmpeg 进程（不代理数据）。返回 (PID, null) 或 (0, errorMessage)。
    /// FFmpeg 通过 TCP 直连 IIS，消除中转延迟。
    /// </summary>
    public static async Task<(int Pid, string? Error)> LaunchFFmpegInAgentAsync(string ffmpegArgs, CancellationToken ct)
    {
        await EnsureAgentRunningAsync();

        var response = await SendCommandInternalAsync(
            JsonSerializer.Serialize(new { type = "launch_ffmpeg", args = ffmpegArgs }),
            TimeSpan.FromSeconds(10));

        if (response == null)
            return (0, "代理未响应（超时或未运行）");

        var json = Encoding.UTF8.GetString(response);
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        if (!root.TryGetProperty("ok", out var okProp) || !okProp.GetBoolean())
        {
            var err = root.TryGetProperty("error", out var errProp) ? errProp.GetString() : "未知错误";
            return (0, err);
        }

        var pid = root.GetProperty("pid").GetInt32();
        Console.WriteLine($"[DesktopAgent] FFmpeg 已在交互式会话启动 (PID: {pid})");
        return (pid, null);
    }

    /// <summary>
    /// 通过代理启动 FFmpeg 流式传输（旧方案，通过管道代理数据，延迟较高）。
    /// 调用方负责在完成后 Dispose 返回的 stream。
    /// </summary>
    public static async Task<(Stream? Stream, string? Error)> StartFFmpegStreamAsync(string ffmpegArgs, CancellationToken ct)
    {
        await EnsureAgentRunningAsync();

        NamedPipeClientStream? pipeClient = null;
        try
        {
            pipeClient = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);

            try
            {
                await pipeClient.ConnectAsync(10000, ct);
            }
            catch (TimeoutException)
            {
                pipeClient.Dispose();
                return (null, "无法连接到桌面代理（连接超时）。代理可能未启动成功。请检查 IIS 应用程序池是否以 LocalSystem 运行。");
            }

            // 发送 start_ffmpeg 命令
            var command = JsonSerializer.Serialize(new { type = "start_ffmpeg", args = ffmpegArgs });
            await WriteMessageAsync(pipeClient, Encoding.UTF8.GetBytes(command), ct);

            // 读取 OK 响应（带帧头的消息）
            var responseBytes = await ReadMessageAsync(pipeClient, ct);
            if (responseBytes == null)
            {
                pipeClient.Dispose();
                return (null, "桌面代理未返回响应（管道已关闭）。代理进程可能已崩溃。");
            }

            var responseJson = Encoding.UTF8.GetString(responseBytes);
            using var doc = JsonDocument.Parse(responseJson);
            if (!doc.RootElement.TryGetProperty("ok", out var okProp) || !okProp.GetBoolean())
            {
                var error = doc.RootElement.TryGetProperty("error", out var errProp)
                    ? errProp.GetString() : "未知错误";
                Console.WriteLine($"[DesktopAgent] FFmpeg 流启动失败: {error}");
                pipeClient.Dispose();
                return (null, $"桌面代理报告错误: {error}");
            }

            Console.WriteLine("[DesktopAgent] FFmpeg 流已就绪，返回管道流给调用方");
            // 返回管道流 —— 之后的数据都是原始 FFmpeg 视频字节（无帧头）
            return (pipeClient, null);
        }
        catch (OperationCanceledException)
        {
            pipeClient?.Dispose();
            return (null, "操作已取消");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[DesktopAgent] 启动 FFmpeg 流失败: {ex.Message}");
            pipeClient?.Dispose();
            return (null, $"连接桌面代理异常: {ex.Message}");
        }
    }

    /// <summary>
    /// 发送命令到代理并获取响应
    /// </summary>
    private static async Task<byte[]?> SendCommandInternalAsync(string commandJson, TimeSpan timeout)
    {
        using var cts = new CancellationTokenSource(timeout);
        try
        {
            using var pipeClient = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            await pipeClient.ConnectAsync((int)timeout.TotalMilliseconds, cts.Token);

            var requestBytes = Encoding.UTF8.GetBytes(commandJson);
            await WriteMessageAsync(pipeClient, requestBytes, cts.Token);

            return await ReadMessageAsync(pipeClient, cts.Token);
        }
        catch (OperationCanceledException)
        {
            Log($"命令超时: {commandJson}, timeout={timeout.TotalMilliseconds}ms");
            return null;
        }
        catch (Exception ex)
        {
            LogException($"发送命令失败: {commandJson}", ex);
            return null;
        }
    }

    #endregion

    #region 协议辅助方法（4字节长度前缀 + 数据）

    /// <summary>
    /// 写入带长度前缀的消息
    /// </summary>
    private static async Task WriteMessageAsync(Stream stream, byte[] data, CancellationToken ct)
    {
        var lengthBytes = BitConverter.GetBytes(data.Length);
        await stream.WriteAsync(lengthBytes, ct);
        await stream.WriteAsync(data, ct);
        await stream.FlushAsync(ct);
    }

    /// <summary>
    /// 读取带长度前缀的消息
    /// </summary>
    private static async Task<byte[]?> ReadMessageAsync(Stream stream, CancellationToken ct)
    {
        var lengthBytes = new byte[4];
        var bytesRead = await ReadExactAsync(stream, lengthBytes, 4, ct);
        if (bytesRead < 4) return null;

        var length = BitConverter.ToInt32(lengthBytes, 0);
        if (length <= 0 || length > 50 * 1024 * 1024) // 最大 50MB（用于截图）
            return null;

        var data = new byte[length];
        bytesRead = await ReadExactAsync(stream, data, length, ct);
        if (bytesRead < length) return null;

        return data;
    }

    /// <summary>
    /// 精确读取指定字节数
    /// </summary>
    private static async Task<int> ReadExactAsync(Stream stream, byte[] buffer, int count, CancellationToken ct)
    {
        int totalRead = 0;
        while (totalRead < count)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(totalRead, count - totalRead), ct);
            if (read == 0) break;
            totalRead += read;
        }
        return totalRead;
    }

    #endregion
}
