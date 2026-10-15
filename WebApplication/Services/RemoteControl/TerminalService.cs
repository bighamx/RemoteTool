using System.Diagnostics;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

namespace RemoteTool.WebApi.Services.RemoteControl;

/// <summary>终端类型：Linux 仅支持 Shell；Windows 支持 PowerShell/Cmd，Shell 映射为 Cmd。</summary>
public enum TerminalType
{
    Shell,
    PowerShell,
    Cmd
}

public class TerminalService(IConfiguration configuration)
{
    public async Task HandleWebSocketAsync(WebSocket webSocket, TerminalType terminalType = TerminalType.Shell)
    {
        if (OperatingSystem.IsMacOS())
        {
            await MacPtySession.RunAsync(webSocket, configuration);
            return;
        }
        var session = new TerminalSession(webSocket, terminalType);

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
        }
    }
}

// The macOS helper owns a real pseudo-terminal. Its stdout is the terminal byte stream;
// stdin uses length-framed input/resize messages so control data never reaches the shell.
internal static class MacPtySession
{
    public static async Task RunAsync(WebSocket socket, IConfiguration configuration)
    {
        var desktopAgent = configuration["RemoteControl:MacAgentExecutable"];
        var helper = configuration["RemoteControl:MacPtyExecutable"];
        if (string.IsNullOrWhiteSpace(helper) && !string.IsNullOrWhiteSpace(desktopAgent))
            helper = Path.Combine(Path.GetDirectoryName(desktopAgent)!, "RemoteToolPtyAgent");
        if (string.IsNullOrWhiteSpace(helper) || !File.Exists(helper))
        {
            await socket.SendAsync(Encoding.UTF8.GetBytes("\r\n[Mac PTY helper 未安装，请重新构建桌面代理]\r\n"), WebSocketMessageType.Text, true, CancellationToken.None);
            await socket.CloseAsync(WebSocketCloseStatus.InternalServerError, "PTY unavailable", CancellationToken.None);
            return;
        }

        using var process = Process.Start(new ProcessStartInfo(helper) {
            UseShellExecute = false, RedirectStandardInput = true, RedirectStandardOutput = true,
            RedirectStandardError = false, CreateNoWindow = true,
            WorkingDirectory = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
        }) ?? throw new InvalidOperationException("无法启动 Mac PTY helper");
        try
        {
            var output = PumpOutputAsync(process, socket);
            var input = PumpInputAsync(process, socket);
            await Task.WhenAny(output, input, process.WaitForExitAsync());
        }
        finally
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { }
            if (socket.State == WebSocketState.Open)
                try { await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "", CancellationToken.None); }
                catch (WebSocketException) { }
        }
    }

    private static async Task PumpOutputAsync(Process process, WebSocket socket)
    {
        var chars = new char[4096];
        while (socket.State == WebSocketState.Open)
        {
            var count = await process.StandardOutput.ReadAsync(chars);
            if (count == 0) break;
            await socket.SendAsync(Encoding.UTF8.GetBytes(chars.AsSpan(0, count).ToString()), WebSocketMessageType.Text, true, CancellationToken.None);
        }
    }

    private static async Task PumpInputAsync(Process process, WebSocket socket)
    {
        var buffer = new byte[65536];
        while (socket.State == WebSocketState.Open)
        {
            var message = await WebSocketTextReader.Read(socket, buffer, CancellationToken.None);
            if (message is null) break;
            byte opcode = 1;
            byte[] payload;
            if (message.StartsWith('{'))
            {
                using var parsed = JsonDocument.Parse(message);
                var root = parsed.RootElement;
                var type = root.GetProperty("type").GetString();
                if (type == "resize")
                {
                    var cols = Math.Clamp(root.GetProperty("cols").GetInt32(), 20, 500);
                    var rows = Math.Clamp(root.GetProperty("rows").GetInt32(), 5, 200);
                    payload = [(byte)cols, (byte)(cols >> 8), (byte)rows, (byte)(rows >> 8)];
                    opcode = 2;
                }
                else if (type == "input") payload = Encoding.UTF8.GetBytes(root.GetProperty("data").GetString() ?? "");
                else continue;
            }
            else payload = Encoding.UTF8.GetBytes(message + "\r");
            if (payload.Length > 65536) continue;
            var header = new byte[5];
            header[0] = opcode;
            BitConverter.TryWriteBytes(header.AsSpan(1), payload.Length);
            await process.StandardInput.BaseStream.WriteAsync(header);
            await process.StandardInput.BaseStream.WriteAsync(payload);
            await process.StandardInput.BaseStream.FlushAsync();
        }
    }
}

public class TerminalSession : IDisposable
{
    private readonly WebSocket _webSocket;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
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
        _useUtf8 = !OperatingSystem.IsWindows() || IsSystemUtf8Enabled();
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
        if (_terminalType == TerminalType.Shell || !OperatingSystem.IsWindows())
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

        if (!OperatingSystem.IsWindows() || _terminalType == TerminalType.Shell)
        {
            // The Web terminal sends complete lines over pipes, not a PTY. On macOS,
            // an interactive zsh loads prompt plugins that cannot run with TERM=dumb.
            var utf8 = new UTF8Encoding(false);
            startInfo.FileName = OperatingSystem.IsMacOS() ? "/bin/zsh" : OperatingSystem.IsLinux() ? "/bin/bash" : "cmd.exe";
            startInfo.Arguments = OperatingSystem.IsMacOS() ? "-s" : OperatingSystem.IsLinux() ? "-i" : "/Q /K";
            startInfo.StandardInputEncoding = utf8;
            startInfo.StandardOutputEncoding = utf8;
            startInfo.StandardErrorEncoding = utf8;
            if (!OperatingSystem.IsWindows())
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
                    break;
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
                // Wait for the complete UTF-8 message before executing a command.
                var command = await WebSocketTextReader.Read(_webSocket, buffer, CancellationToken.None, 65536);
                if (command is null) break;
                if (_process != null && !_process.HasExited)
                {
                    var lineEnd = !OperatingSystem.IsWindows() ? "\n" : "\r\n";

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
        await _sendGate.WaitAsync();
        try {
        if (_webSocket.State == WebSocketState.Open)
        {
            try
            {
                var bytes = Encoding.UTF8.GetBytes(message);
                await _webSocket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
            }
            catch (Exception error) when (error is WebSocketException or ObjectDisposedException or InvalidOperationException)
            {
                // Connection closed
            }
        }
        } finally { _sendGate.Release(); }
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
