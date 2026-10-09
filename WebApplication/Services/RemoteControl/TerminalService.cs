using System.Diagnostics;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

/// <summary>终端类型：Linux 仅支持 Shell；Windows 支持 PowerShell/Cmd，Shell 映射为 Cmd。</summary>
public enum TerminalType
{
    Shell,
    PowerShell,
    Cmd
}

public class TerminalService
{
    private readonly Dictionary<string, TerminalSession> _sessions = new();

    public async Task HandleWebSocketAsync(WebSocket webSocket, TerminalType terminalType = TerminalType.Shell)
    {
        var sessionId = Guid.NewGuid().ToString();
        var session = new TerminalSession(webSocket, terminalType);
        _sessions[sessionId] = session;

        try
        {
            await session.StartAsync();
            await session.ReceiveLoopAsync();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Terminal session error: {ex.Message}");
        }
        finally
        {
            session.Dispose();
            _sessions.Remove(sessionId);
        }
    }
}

public class TerminalSession : IDisposable
{
    private readonly WebSocket _webSocket;
    private readonly TerminalType _terminalType;
    private Process? _process;
    private bool _disposed;
    private readonly bool _useUtf8;  // 是否使用 UTF-8 编码
    /// <summary>未启用 UTF-8 时使用的系统默认编码（仅 Windows，从注册表 ACP 读取）。</summary>
    private readonly Encoding? _systemEncoding;

    public TerminalSession(WebSocket webSocket, TerminalType terminalType)
    {
        _webSocket = webSocket;
        _terminalType = terminalType;
        _useUtf8 = RuntimeInformation.IsOSPlatform(OSPlatform.Linux) || IsSystemUtf8Enabled();
        if (!_useUtf8 && RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            _systemEncoding = GetSystemDefaultEncoding();
        else
            _systemEncoding = null;
    }

    /// <summary>
    /// 检测系统是否启用了 "Beta版: 使用 Unicode UTF-8 提供全球语言支持"（仅 Windows）
    /// </summary>
    private static bool IsSystemUtf8Enabled()
    {
        try
        {
            // 检查注册表：HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Nls\CodePage
            // ACP = 65001 表示启用了 UTF-8 全局支持
            using var key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Nls\CodePage");
            if (key != null)
            {
                var acp = key.GetValue("ACP") as string;
                return acp == "65001";
            }
        }
        catch
        {
            // 无法读取注册表，保守视为未启用 UTF-8
        }
        return false;
    }

    /// <summary>
    /// 获取 Windows 系统默认 ANSI 代码页对应的编码（仅当未启用 UTF-8 时使用）。
    /// </summary>
    private static Encoding GetSystemDefaultEncoding()
    {
        var codePage = GetSystemCodePage();
        try
        {
            return Encoding.GetEncoding(codePage);
        }
        catch
        {
            return Encoding.GetEncoding(1252); // 西欧，常见回退
        }
    }

    /// <summary>
    /// 从注册表读取系统当前 ANSI 代码页（ACP）；读取失败时返回 1252。
    /// </summary>
    private static int GetSystemCodePage()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Nls\CodePage");
            var acp = key?.GetValue("ACP") as string;
            if (acp != null && int.TryParse(acp, out var cp) && cp > 0)
                return cp;
        }
        catch { }
        return 1252; // 西欧，常见回退
    }

    public async Task StartAsync()
    {
        _process = new Process
        {
            StartInfo = CreateStartInfo(),
            EnableRaisingEvents = true
        };

        _process.Exited += async (sender, e) =>
        {
            await SendMessageAsync("\r\n[Process exited]\r\n");
        };

        _process.Start();

        // 统一初始设置（Shell 不发送编码/清屏命令）
        if (_terminalType == TerminalType.Shell)
        {
            // Linux/Shell：不发送任何初始化命令，保持原生 shell
        }
        else if (_terminalType == TerminalType.PowerShell)
        {
            var codePage = _useUtf8 ? 65001 : (_systemEncoding?.CodePage ?? GetSystemCodePage());
            await _process.StandardInput.WriteLineAsync($"[Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding({codePage})");
            await _process.StandardInput.WriteLineAsync($"[Console]::InputEncoding = [System.Text.Encoding]::GetEncoding({codePage})");
            await _process.StandardInput.WriteLineAsync($"$OutputEncoding = [System.Text.Encoding]::GetEncoding({codePage})");
            await _process.StandardInput.WriteLineAsync("cls");
        }
        else
        {
            if (_useUtf8)
                await _process.StandardInput.WriteLineAsync("chcp 65001 > nul");
            else
                await _process.StandardInput.WriteLineAsync($"chcp {_systemEncoding?.CodePage ?? GetSystemCodePage()} > nul");
            await _process.StandardInput.FlushAsync();
            await Task.Delay(100);
            await _process.StandardInput.WriteLineAsync("cls");
            await _process.StandardInput.FlushAsync();
        }

        // Start background tasks to read output streams
        _ = ReadStreamAsync(_process.StandardOutput);
        _ = ReadStreamAsync(_process.StandardError);
    }

    private ProcessStartInfo CreateStartInfo()
    {
        var startInfo = new ProcessStartInfo
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
        };

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux) || _terminalType == TerminalType.Shell)
        {
            // Linux 或显式选择 Shell：使用 /bin/bash -i（交互式），UTF-8
            var utf8 = new UTF8Encoding(false);
            startInfo.FileName = RuntimeInformation.IsOSPlatform(OSPlatform.Linux) ? "/bin/bash" : "cmd.exe";
            startInfo.Arguments = RuntimeInformation.IsOSPlatform(OSPlatform.Linux) ? "-i" : "/Q /K";
            startInfo.StandardInputEncoding = utf8;
            startInfo.StandardOutputEncoding = utf8;
            startInfo.StandardErrorEncoding = utf8;
            if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                startInfo.WorkingDirectory = Environment.GetEnvironmentVariable("HOME") ?? "/";
        }
        else if (_terminalType == TerminalType.PowerShell)
        {
            startInfo.FileName = "powershell.exe";
            startInfo.Arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass";
            if (_useUtf8)
            {
                var utf8NoBom = new UTF8Encoding(false);
                startInfo.StandardInputEncoding = utf8NoBom;
                startInfo.StandardOutputEncoding = utf8NoBom;
                startInfo.StandardErrorEncoding = utf8NoBom;
            }
            else
            {
                var enc = _systemEncoding!;
                startInfo.StandardInputEncoding = enc;
                startInfo.StandardOutputEncoding = enc;
                startInfo.StandardErrorEncoding = enc;
            }
        }
        else
        {
            startInfo.FileName = "cmd.exe";
            if (_useUtf8)
            {
                var utf8NoBom = new UTF8Encoding(false);
                startInfo.StandardInputEncoding = utf8NoBom;
                startInfo.StandardOutputEncoding = utf8NoBom;
                startInfo.StandardErrorEncoding = utf8NoBom;
            }
            else
            {
                var enc = _systemEncoding!;
                startInfo.StandardInputEncoding = enc;
                startInfo.StandardOutputEncoding = enc;
                startInfo.StandardErrorEncoding = enc;
            }
        }

        return startInfo;
    }

    private async Task ReadStreamAsync(StreamReader reader)
    {
        try
        {
            var buffer = new char[256];
            while (!_disposed && _process != null && !_process.HasExited)
            {
                // 使用 ReadAsync 实时读取字符，而不是按行读取
                // 这样可以立即显示命令提示符等不以换行符结尾的输出
                var bytesRead = await reader.ReadAsync(buffer, 0, buffer.Length);
                if (bytesRead == 0)
                {
                    // 没有数据可读，短暂等待后重试
                    await Task.Delay(10);
                    continue;
                }

                var output = new string(buffer, 0, bytesRead);
                await SendMessageAsync(output);
            }
        }
        catch (Exception)
        {
            // Stream closed or process exited
        }
    }

    public async Task ReceiveLoopAsync()
    {
        var buffer = new byte[4096];

        while (_webSocket.State == WebSocketState.Open && !_disposed)
        {
            try
            {
                var result = await _webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await _webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None);
                    break;
                }

                if (result.MessageType == WebSocketMessageType.Text && _process != null && !_process.HasExited)
                {
                    var command = Encoding.UTF8.GetString(buffer, 0, result.Count);
                    var lineEnd = RuntimeInformation.IsOSPlatform(OSPlatform.Linux) ? "\n" : "\r\n";

                    if (_terminalType == TerminalType.Shell || RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                    {
                        var bytes = _process.StandardInput.Encoding.GetBytes(command + lineEnd);
                        await _process.StandardInput.BaseStream.WriteAsync(bytes);
                        await _process.StandardInput.BaseStream.FlushAsync();
                    }
                    else if (_terminalType == TerminalType.Cmd && !_useUtf8)
                    {
                        var enc = _systemEncoding!;
                        var bytes = enc.GetBytes(command + "\r\n");
                        await _process.StandardInput.BaseStream.WriteAsync(bytes);
                        await _process.StandardInput.BaseStream.FlushAsync();
                    }
                    else if (_terminalType == TerminalType.PowerShell)
                    {
                        if (_useUtf8)
                        {
                            await _process.StandardInput.WriteLineAsync(command);
                            await _process.StandardInput.FlushAsync();
                        }
                        else
                        {
                            var enc = _systemEncoding!;
                            var bytes = enc.GetBytes(command + "\r\n");
                            await _process.StandardInput.BaseStream.WriteAsync(bytes);
                            await _process.StandardInput.BaseStream.FlushAsync();
                        }
                    }
                    else
                    {
                        await _process.StandardInput.WriteLineAsync(command);
                        await _process.StandardInput.FlushAsync();
                    }
                }
            }
            catch (WebSocketException)
            {
                break;
            }
        }
    }

    private async Task SendMessageAsync(string message)
    {
        if (_webSocket.State == WebSocketState.Open)
        {
            try
            {
                var bytes = Encoding.UTF8.GetBytes(message);
                await _webSocket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
            }
            catch (WebSocketException)
            {
                // Connection closed
            }
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        try
        {
            if (_process != null && !_process.HasExited)
            {
                _process.Kill(true);
            }
            _process?.Dispose();
        }
        catch { }
    }
}
