using System.Diagnostics;
using System.ComponentModel;
using System.Security.Principal;
using System.Runtime.InteropServices;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

/// <summary>
/// 提供在交互式桌面会话中启动进程的能力，用于绕过 IIS/Windows Service 的 Session 0 隔离。
/// 要求应用程序池以 LocalSystem 身份运行。
/// </summary>
public static class InteractiveProcessLauncher
{
    private static void Log(string message)
        => AgentStartupLogger.Log("InteractiveProcessLauncher", message);

    private static void LogException(string message, Exception ex)
        => AgentStartupLogger.LogException("InteractiveProcessLauncher", message, ex);

    private static string FormatWin32Error(int errorCode)
    {
        if (errorCode == 0)
            return "0 (无错误信息)";

        try
        {
            var message = new Win32Exception(errorCode).Message;
            return $"{errorCode} ({message})";
        }
        catch
        {
            return errorCode.ToString();
        }
    }

    #region Win32 API 声明

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentProcessId();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool ProcessIdToSessionId(uint dwProcessId, out uint pSessionId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr phToken);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern bool DuplicateTokenEx(
        IntPtr hExistingToken,
        uint dwDesiredAccess,
        IntPtr lpTokenAttributes,
        int impersonationLevel,
        int tokenType,
        out IntPtr phNewToken);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CreateProcessAsUser(
        IntPtr hToken,
        string? lpApplicationName,
        string lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string? lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("userenv.dll", SetLastError = true)]
    private static extern bool CreateEnvironmentBlock(out IntPtr lpEnvironment, IntPtr hToken, bool bInherit);

    [DllImport("userenv.dll", SetLastError = true)]
    private static extern bool DestroyEnvironmentBlock(IntPtr lpEnvironment);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint processAccess, bool bInheritHandle, int processId);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out IntPtr tokenHandle);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(IntPtr tokenHandle, int tokenInformationClass,
        IntPtr tokenInformation, uint tokenInformationLength, out uint returnLength);

    [DllImport("advapi32.dll")]
    private static extern IntPtr GetSidSubAuthority(IntPtr pSid, uint n);

    [DllImport("advapi32.dll")]
    private static extern IntPtr GetSidSubAuthorityCount(IntPtr pSid);

    [DllImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ConvertSidToStringSid(IntPtr pSid, out IntPtr ptrSid);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);

    private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private const uint TOKEN_QUERY = 0x0008;
    private const uint TOKEN_DUPLICATE = 0x0002;
    private const int TokenUser = 1;
    private const int TokenIntegrityLevel = 25;
    private const uint SECURITY_MANDATORY_HIGH_RID = 0x3000;
    private const string SidLocalSystem = "S-1-5-18";
    private const string SidLocalService = "S-1-5-19";
    private const string SidNetworkService = "S-1-5-20";

    [StructLayout(LayoutKind.Sequential)]
    private struct SID_AND_ATTRIBUTES
    {
        public IntPtr Sid;
        public uint Attributes;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct TOKEN_MANDATORY_LABEL
    {
        public SID_AND_ATTRIBUTES Label;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct TOKEN_USER
    {
        public SID_AND_ATTRIBUTES User;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public int cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public int dwProcessId;
        public int dwThreadId;
    }

    private const uint TOKEN_ALL_ACCESS = 0x000F01FF;
    private const int SecurityImpersonation = 2;
    private const int TokenPrimary = 1;
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private const uint CREATE_NEW_CONSOLE = 0x00000010;

    #endregion

    private static bool? _isSession0;
    private static readonly object _lock = new();

    /// <summary>
    /// 在指定会话中查找高完整性进程并复制其主令牌，用于以“管理员身份”创建子进程。调用方须 CloseHandle 返回的令牌。
    /// 若该会话中没有任何以管理员运行的进程（如未打开过任务管理器），则返回 IntPtr.Zero。
    /// </summary>
    private static bool IsServiceAccountSid(string sid)
        => string.Equals(sid, SidLocalSystem, StringComparison.OrdinalIgnoreCase)
            || string.Equals(sid, SidLocalService, StringComparison.OrdinalIgnoreCase)
            || string.Equals(sid, SidNetworkService, StringComparison.OrdinalIgnoreCase);

    private static string? ResolveAccountNameFromSid(string sid)
    {
        try
        {
            var securityIdentifier = new SecurityIdentifier(sid);
            var account = securityIdentifier.Translate(typeof(NTAccount));
            return account.Value;
        }
        catch
        {
            return null;
        }
    }

    private static string? GetTokenUserSidString(IntPtr tokenHandle)
    {
        IntPtr buffer = IntPtr.Zero;
        IntPtr sidStringPtr = IntPtr.Zero;
        try
        {
            if (!GetTokenInformation(tokenHandle, TokenUser, IntPtr.Zero, 0, out var needed) || needed == 0)
                return null;

            buffer = Marshal.AllocHGlobal((int)needed);
            if (!GetTokenInformation(tokenHandle, TokenUser, buffer, needed, out _))
                return null;

            var tokenUser = Marshal.PtrToStructure<TOKEN_USER>(buffer);
            if (tokenUser.User.Sid == IntPtr.Zero)
                return null;

            if (!ConvertSidToStringSid(tokenUser.User.Sid, out sidStringPtr) || sidStringPtr == IntPtr.Zero)
                return null;

            return Marshal.PtrToStringUni(sidStringPtr);
        }
        finally
        {
            if (sidStringPtr != IntPtr.Zero)
                LocalFree(sidStringPtr);
            if (buffer != IntPtr.Zero)
                Marshal.FreeHGlobal(buffer);
        }
    }

    private static string DescribeTokenUser(IntPtr tokenHandle)
    {
        var sid = GetTokenUserSidString(tokenHandle) ?? "<unknown-sid>";
        var account = ResolveAccountNameFromSid(sid) ?? "<unknown-account>";
        return $"{account} ({sid})";
    }

    private static IntPtr TryGetElevatedTokenForSession(uint targetSessionId, string? expectedUserSid)
    {
        const uint TOKEN_QUERY_DUPLICATE = TOKEN_QUERY | TOKEN_DUPLICATE;
        Log($"开始查找会话 {targetSessionId} 的高完整性令牌，期望用户 SID={expectedUserSid ?? "<unknown>"}");

        foreach (var proc in Process.GetProcesses())
        {
            IntPtr hProcess = IntPtr.Zero;
            IntPtr hToken = IntPtr.Zero;
            try
            {
                if (!ProcessIdToSessionId((uint)proc.Id, out uint sid) || sid != targetSessionId)
                    continue;

                hProcess = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, proc.Id);
                if (hProcess == IntPtr.Zero)
                    continue;

                if (!OpenProcessToken(hProcess, TOKEN_QUERY_DUPLICATE, out hToken) || hToken == IntPtr.Zero)
                    continue;

                var candidateSid = GetTokenUserSidString(hToken);
                if (string.IsNullOrWhiteSpace(candidateSid))
                    continue;

                if (IsServiceAccountSid(candidateSid))
                    continue;

                if (!string.IsNullOrWhiteSpace(expectedUserSid)
                    && !string.Equals(candidateSid, expectedUserSid, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                uint len = 0;
                GetTokenInformation(hToken, TokenIntegrityLevel, IntPtr.Zero, 0, out _);
                len = 256;
                var buf = Marshal.AllocHGlobal((int)len);
                try
                {
                    if (!GetTokenInformation(hToken, TokenIntegrityLevel, buf, len, out len))
                        continue;

                    var label = Marshal.PtrToStructure<TOKEN_MANDATORY_LABEL>(buf);
                    IntPtr pSid = label.Label.Sid;
                    if (pSid == IntPtr.Zero)
                        continue;

                    int count = Marshal.ReadByte(GetSidSubAuthorityCount(pSid));
                    if (count <= 0)
                        continue;

                    int level = Marshal.ReadInt32(GetSidSubAuthority(pSid, (uint)(count - 1)));
                    if (level < SECURITY_MANDATORY_HIGH_RID)
                        continue;

                    if (!DuplicateTokenEx(hToken, TOKEN_ALL_ACCESS, IntPtr.Zero, SecurityImpersonation, TokenPrimary, out IntPtr dupToken))
                        continue;

                    Log($"命中高完整性令牌: 进程 PID={proc.Id}, Name={proc.ProcessName}, User={DescribeTokenUser(hToken)}");

                    return dupToken;
                }
                finally
                {
                    Marshal.FreeHGlobal(buf);
                }
            }
            finally
            {
                if (hToken != IntPtr.Zero) CloseHandle(hToken);
                if (hProcess != IntPtr.Zero) CloseHandle(hProcess);
                proc.Dispose();
            }
        }

        Log("未找到符合条件的高完整性令牌");

        return IntPtr.Zero;
    }

    /// <summary>
    /// 判断当前进程是否运行在 Session 0（非交互式会话，如 IIS / Windows Service）
    /// </summary>
    public static bool IsRunningInSession0
    {
        get
        {
            if (_isSession0.HasValue) return _isSession0.Value;
            lock (_lock)
            {
                if (_isSession0.HasValue) return _isSession0.Value;
                if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                {
                    _isSession0 = false;
                    return false;
                }
                if (ProcessIdToSessionId(GetCurrentProcessId(), out uint sessionId))
                {
                    _isSession0 = sessionId == 0;
                    Log($"当前会话 ID: {sessionId}, 是否 Session 0: {_isSession0}");
                }
                else
                {
                    _isSession0 = false;
                }
                return _isSession0.Value;
            }
        }
    }

    private static bool TryGetUserTokenForSession(uint sessionId, out IntPtr token, out int errorCode)
    {
        token = IntPtr.Zero;
        errorCode = 0;

        if (WTSQueryUserToken(sessionId, out token) && token != IntPtr.Zero)
            return true;

        errorCode = Marshal.GetLastWin32Error();
        if (token != IntPtr.Zero)
            CloseHandle(token);
        token = IntPtr.Zero;
        return false;
    }

    private static List<uint> BuildCandidateSessionIds(uint preferredSessionId)
    {
        var result = new List<uint>();
        var seen = new HashSet<uint>();

        void AddSession(uint sid)
        {
            if (sid == 0 || sid == 0xFFFFFFFF)
                return;
            if (seen.Add(sid))
                result.Add(sid);
        }

        AddSession(preferredSessionId);

        foreach (var proc in Process.GetProcesses())
        {
            try
            {
                if (ProcessIdToSessionId((uint)proc.Id, out var sid))
                    AddSession(sid);
            }
            catch
            {
                // 忽略单个进程访问失败
            }
            finally
            {
                proc.Dispose();
            }
        }

        return result;
    }

    private static bool TryResolveInteractiveUserToken(
        uint preferredSessionId,
        out IntPtr token,
        out uint resolvedSessionId,
        out string detail)
    {
        token = IntPtr.Zero;
        resolvedSessionId = 0;

        var candidates = BuildCandidateSessionIds(preferredSessionId);
        if (candidates.Count == 0)
        {
            detail = "未发现任何候选交互会话（非 Session 0）";
            return false;
        }

        var attempts = new List<string>();
        foreach (var sid in candidates)
        {
            if (TryGetUserTokenForSession(sid, out token, out var errorCode))
            {
                resolvedSessionId = sid;
                detail = $"成功获取会话 {sid} 的用户令牌";
                return true;
            }

            attempts.Add($"{sid}:{FormatWin32Error(errorCode)}");
        }

        detail = $"候选会话均无法获取用户令牌，尝试结果={string.Join(", ", attempts)}";
        return false;
    }

    /// <summary>
    /// 在交互式桌面会话中启动进程。成功时返回 (true, processId)，失败返回 (false, 0)。
    /// 要求调用进程以 LocalSystem 身份运行（IIS 应用程序池需配置为 LocalSystem）。
    /// 当 useElevatedTokenIfAvailable 为 true 时，采用两种方式争取以管理员身份启动（可同时保留）：
    /// 1）优先在本会话中查找已存在的高完整性进程并复制其令牌（无需密码，仅当用户曾以管理员运行过某进程时有效）；
    /// 2）若配置了 RemoteControl:ElevatedAgent 凭据，应用启动时会创建“登录时以最高权限运行”的计划任务，用户注销并重新登录后代理将以管理员身份启动。
    /// 若未取得高完整性令牌则回退到普通用户令牌启动。
    /// </summary>
    /// <param name="useElevatedTokenIfAvailable">为 true 时先尝试用本会话内高完整性令牌启动，若无则用普通用户令牌。</param>
    public static (bool Success, int ProcessId) LaunchInInteractiveSession(string commandLine, string? workingDirectory = null, bool useElevatedTokenIfAvailable = false)
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
        {
            Log("仅支持 Windows 平台");
            return (false, 0);
        }

        Log($"准备在交互式会话启动进程，commandLine={commandLine}, workingDirectory={(string.IsNullOrWhiteSpace(workingDirectory) ? "<null>" : workingDirectory)}, useElevatedTokenIfAvailable={useElevatedTokenIfAvailable}");

        var consoleSessionId = WTSGetActiveConsoleSessionId();
        if (consoleSessionId == 0xFFFFFFFF)
            Log("无法获取活动控制台会话 ID，将尝试自动发现可用交互会话");
        else
            Log($"活动控制台会话 ID: {consoleSessionId}");

        IntPtr userToken = IntPtr.Zero;
        IntPtr interactiveUserToken = IntPtr.Zero;
        IntPtr duplicateToken = IntPtr.Zero;
        IntPtr environment = IntPtr.Zero;
        uint sessionId = 0;

        try
        {
            if (!TryResolveInteractiveUserToken(consoleSessionId, out interactiveUserToken, out sessionId, out var tokenResolveDetail))
            {
                Log($"未找到可用交互用户令牌：{tokenResolveDetail}。请确保有用户登录到桌面会话（本地或 RDP 活动会话）。");
                return (false, 0);
            }

            Log($"目标会话 ID: {sessionId}，{tokenResolveDetail}");

            var interactiveUserSid = GetTokenUserSidString(interactiveUserToken);
            Log($"交互用户令牌: {DescribeTokenUser(interactiveUserToken)}");

            if (useElevatedTokenIfAvailable)
            {
                if (!string.IsNullOrWhiteSpace(interactiveUserSid))
                {
                    userToken = TryGetElevatedTokenForSession(sessionId, interactiveUserSid);
                    if (userToken != IntPtr.Zero)
                        Log($"使用该会话中的高完整性令牌（管理员身份）启动，令牌用户: {DescribeTokenUser(userToken)}");
                    else
                        Log("未找到高完整性进程令牌，将使用普通用户令牌；可配置 RemoteControl:ElevatedAgent 凭据并在登录时以管理员运行");
                }
                else
                {
                    Log("交互用户 SID 解析失败，跳过高完整性令牌扫描，直接回退普通用户令牌");
                }
            }

            if (userToken == IntPtr.Zero)
            {
                userToken = interactiveUserToken;
                interactiveUserToken = IntPtr.Zero;

                Log($"回退到普通用户令牌启动，令牌用户: {DescribeTokenUser(userToken)}");
            }

            Log($"最终用于 CreateProcessAsUser 的原始令牌用户: {DescribeTokenUser(userToken)}");

            if (!DuplicateTokenEx(userToken, TOKEN_ALL_ACCESS, IntPtr.Zero,
                SecurityImpersonation, TokenPrimary, out duplicateToken))
            {
                var error = Marshal.GetLastWin32Error();
                Log($"DuplicateTokenEx 失败，错误: {FormatWin32Error(error)}");
                return (false, 0);
            }

            Log($"DuplicateTokenEx 成功，得到主令牌，令牌用户: {DescribeTokenUser(duplicateToken)}");

            if (!CreateEnvironmentBlock(out environment, duplicateToken, false))
            {
                var envError = Marshal.GetLastWin32Error();
                Log($"CreateEnvironmentBlock 失败，将继续以空环境启动，错误: {FormatWin32Error(envError)}");
                environment = IntPtr.Zero;
            }
            else
            {
                Log("CreateEnvironmentBlock 成功");
            }

            var si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(si);
            si.lpDesktop = @"winsta0\default"; // 指向交互式桌面

            var creationFlags = CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW;

            if (!CreateProcessAsUser(duplicateToken, null, commandLine,
                IntPtr.Zero, IntPtr.Zero, false, creationFlags,
                environment, workingDirectory, ref si, out var pi))
            {
                var error = Marshal.GetLastWin32Error();
                Log($"CreateProcessAsUser 失败，错误: {FormatWin32Error(error)}，commandLine={commandLine}, desktop={si.lpDesktop}");
                return (false, 0);
            }

            var processId = pi.dwProcessId;
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);

            Log($"成功在会话 {sessionId} 中启动进程 (PID: {processId}): {commandLine}");
            return (true, processId);
        }
        catch (Exception ex)
        {
            LogException("LaunchInInteractiveSession 异常", ex);
            return (false, 0);
        }
        finally
        {
            if (environment != IntPtr.Zero) DestroyEnvironmentBlock(environment);
            if (duplicateToken != IntPtr.Zero) CloseHandle(duplicateToken);
            if (interactiveUserToken != IntPtr.Zero) CloseHandle(interactiveUserToken);
            if (userToken != IntPtr.Zero) CloseHandle(userToken);
        }
    }

    /// <summary>
    /// 获取当前应用程序 DLL 的完整路径（用于启动桌面代理）。
    /// 使用 AppContext.BaseDirectory 而非 Assembly.Location，
    /// 以避免 IIS 影子复制（Shadow Copy）导致路径指向临时目录。
    /// </summary>
    public static string GetApplicationDllPath()
    {
        var baseDir = AppContext.BaseDirectory;
        var assemblyName = typeof(InteractiveProcessLauncher).Assembly.GetName().Name + ".dll";
        var dllPath = Path.Combine(baseDir, assemblyName);

        // 验证路径存在
        if (File.Exists(dllPath))
        {
            Log($"应用 DLL 路径: {dllPath}");
            return dllPath;
        }

        // 回退到 Assembly.Location
        var fallback = typeof(InteractiveProcessLauncher).Assembly.Location;
        Log($"回退 DLL 路径: {fallback}");
        return fallback;
    }

    /// <summary>
    /// 获取 dotnet 可执行文件的路径
    /// </summary>
    public static string GetDotnetPath()
    {
        // 优先使用环境变量
        var dotnetPath = Environment.GetEnvironmentVariable("DOTNET_HOST_PATH");
        if (!string.IsNullOrEmpty(dotnetPath) && File.Exists(dotnetPath))
        {
            Log($"使用 DOTNET_HOST_PATH: {dotnetPath}");
            return dotnetPath;
        }

        // 常见安装路径
        var programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        var defaultPath = Path.Combine(programFiles, "dotnet", "dotnet.exe");
        if (File.Exists(defaultPath))
        {
            Log($"使用默认 dotnet 路径: {defaultPath}");
            return defaultPath;
        }

        // 回退到 PATH 中的 dotnet
        Log("dotnet.exe 未在固定路径找到，回退到 PATH 中的 dotnet");
        return "dotnet";
    }

    /// <summary>
    /// 计划任务名称：登录时以最高权限启动桌面代理（需配置管理员凭据）。
    /// </summary>
    public const string ElevatedAgentTaskName = "ChuckieHelper_DesktopAgent_Elevated";

    /// <summary>
    /// 使用管理员凭据创建“登录时以最高权限运行”的计划任务，使桌面代理在用户下次登录时以管理员身份自动启动。
    /// 调用方须以 LocalSystem 运行；创建后用户需注销并重新登录一次方可生效。
    /// </summary>
    /// <param name="userName">管理员用户名（可为 .\用户名 或 域\用户名）</param>
    /// <param name="password">管理员密码</param>
    /// <param name="domain">域（本地账户可传 null 或空）</param>
    /// <returns>创建成功返回 true</returns>
    public static bool CreateElevatedAgentLogonTask(string userName, string password, string? domain)
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows) || string.IsNullOrWhiteSpace(userName) || string.IsNullOrWhiteSpace(password))
        {
            Log("创建高权限代理任务：需要 Windows 且提供用户名和密码");
            return false;
        }

        var dotnetPath = GetDotnetPath();
        var dllPath = GetApplicationDllPath();
        var cmd = $"\"{dotnetPath}\" \"{dllPath}\" --desktop-agent";
        var ru = string.IsNullOrWhiteSpace(domain) ? userName : $"{domain}\\{userName}";

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "schtasks",
                ArgumentList =
                {
                    "/create",
                    "/tn", ElevatedAgentTaskName,
                    "/tr", cmd,
                    "/sc", "onlogon",
                    "/ru", ru,
                    "/rp", password,
                    "/rl", "HIGHEST",
                    "/f"
                },
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            using var p = Process.Start(psi);
            if (p == null)
            {
                Log("schtasks 启动失败");
                return false;
            }

            var outText = p.StandardOutput.ReadToEnd();
            var errText = p.StandardError.ReadToEnd();
            p.WaitForExit(15000);

            if (p.ExitCode == 0)
            {
                Log($"已创建登录时高权限代理任务: {ElevatedAgentTaskName}。请注销并重新登录一次使代理以管理员身份运行。");
                return true;
            }

            Log($"schtasks /create 失败，退出码: {p.ExitCode}, 错误: {errText?.Trim()}");
            return false;
        }
        catch (Exception ex)
        {
            LogException("创建高权限代理任务异常", ex);
            return false;
        }
    }

    /// <summary>
    /// 立即运行已创建的高权限代理计划任务（若存在）。任务由服务触发时可能在 Session 0 运行，若需在交互会话中高权限运行请依赖“登录时”触发。
    /// </summary>
    /// <returns>已发起运行返回 true</returns>
    public static bool RunElevatedAgentTaskNow()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            return false;

        try
        {
            using var p = Process.Start(new ProcessStartInfo
            {
                FileName = "schtasks",
                ArgumentList = { "/run", "/tn", ElevatedAgentTaskName },
                UseShellExecute = false,
                CreateNoWindow = true
            });
            if (p != null)
            {
                p.WaitForExit(5000);
                if (p.ExitCode == 0)
                {
                    Log("已触发高权限代理任务运行");
                    return true;
                }
            }
        }
        catch (Exception ex)
        {
            LogException("运行高权限任务失败", ex);
        }

        return false;
    }
}
