using System.Diagnostics;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services.PhoneControl;

public sealed class PhoneSessionIntegration(PhoneGateway gateway, IConfiguration configuration, ILogger<PhoneSessionIntegration> logger) {
    private readonly SemaphoreSlim installLock = new(1, 1);
    public async Task<JsonObject?> Prepare(JsonObject body, string agent, HttpRequest request, CancellationToken ct) {
        body.Remove("phone_binding"); body.Remove("phone_description");
        if (body["phone_tools"] is not JsonObject options || options["mode"]?.ToString() is null or "off") { body.Remove("phone_tools"); return null; }
        var mode = options["mode"]!.ToString(); if (mode is not ("tools" or "description")) throw new ArgumentException("无效手机能力模式");
        var phone = options["phone_id"]?.ToString() ?? ""; var profile = agent == "hermes" ? HermesProfileScope.Name : "default";
        var binding = gateway.CreateBinding(phone, options["allow_advanced"]?.GetValue<bool>() == true, agent, profile); binding["mode"] = mode;
        var root = configuration["PhoneTools:CallbackBaseUrl"] ?? $"{request.Scheme}://{request.Host}{request.PathBase}";
        binding["url"] = root.TrimEnd('/') + "/api/phone/v1/bound/" + binding["id"];
        try {
            if (agent == "hermes" && mode == "tools") await EnsureHermesPlugin(ct);
            var description = BuildDescription(phone, binding);
            if (agent == "codex") { body["phone_binding"] = binding.DeepClone(); body["phone_description"] = description; }
            else body["system_prompt"] = (body["system_prompt"]?.ToString() ?? "") + "\n\n" + description;
            body.Remove("phone_tools"); return binding;
        } catch { gateway.RemoveBinding(binding["id"]!.ToString()); throw; }
    }
    public void Complete(JsonObject? binding, string session, string agent) {
        if (binding == null) return;
        gateway.BindSession(binding["id"]!.ToString(), session);
        if (agent == "hermes" && binding["mode"]?.ToString() == "tools") {
            var path = Path.Combine(new HermesProfiles(configuration).Home, "remotetool-phone-bindings.json");
            lock (fileLock) {
                var rows = File.Exists(path) ? JsonNode.Parse(File.ReadAllText(path))!.AsObject() : new JsonObject();
                rows[session] = new JsonObject { ["url"] = binding["url"]!.ToString(), ["token"] = binding["token"]!.ToString() };
                var tmp = path + ".tmp-" + Guid.NewGuid().ToString("N");
                try { File.WriteAllText(tmp, rows.ToJsonString()); if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(tmp, UnixFileMode.UserRead | UnixFileMode.UserWrite); File.Move(tmp, path, true); }
                finally { if (File.Exists(tmp)) File.Delete(tmp); }
            }
        }
    }
    public void Abort(JsonObject? binding) { if (binding != null) gateway.RemoveBinding(binding["id"]!.ToString()); }
    private static string BuildDescription(string phone, JsonObject binding) {
        var tools = binding["tools"]!.AsArray().OfType<JsonObject>().ToArray();
        var names = tools.Select(tool => tool["name"]!.ToString()).ToHashSet(StringComparer.Ordinal);
        var enabled = binding["mode"]?.ToString() == "tools";
        var advanced = tools.Any(tool => tool["annotations"]?["destructiveHint"]?.GetValue<bool>() == true);
        var text = new System.Text.StringBuilder();
        text.AppendLine("【RemoteTool Android 手机操作能力】");
        text.AppendLine($"本会话已绑定一部 Android 手机，设备标识：{phone}。这部手机与运行 Agent 的电脑是不同的设备。");
        text.AppendLine(enabled
            ? "你可以通过本会话已注册的 phone_* 工具查看和操作这部手机。用户要求处理手机上的任务时，请结合下列能力执行任务，而不只是提供手动操作步骤。RemoteTool 负责将工具请求经服务端转发到手机，手机与电脑无需处于同一局域网。"
            : "当前仅注入手机能力说明，尚未为本会话启用可调用的 phone_* 工具。下列内容描述可提供的能力，不代表当前已能执行；需要直接操作时，应在新建会话时选择“启用工具”，或使用用户另行配置的可用 MCP 连接。");
        text.AppendLine(enabled ? "本会话提供的能力（实际执行取决于手机状态及已授予的权限）：" : "可提供的手机能力：");
        void Capability(string description, params string[] candidates) {
            var available = candidates.Where(names.Contains).ToArray();
            if (available.Length > 0) text.AppendLine($"- {description}：{string.Join(", ", available)}。");
        }
        Capability("查询手机信息、可用权限后端和已安装应用", "phone_list_devices", "phone_get_capabilities", "phone_list_apps");
        Capability("查看当前屏幕截图、读取界面控件树、按文字或资源标识查找控件", "phone_observe", "phone_screenshot", "phone_find_nodes");
        Capability("点击、滑动、输入文字、返回/Home 等按键、控件操作及多指路径手势", "phone_tap", "phone_swipe", "phone_input_text", "phone_key", "phone_ui_action", "phone_gesture");
        Capability("启动手机应用，并等待指定界面文字或前台应用出现", "phone_launch_app", "phone_wait");
        Capability("声明 UI 操作开始/结束、断开本 Agent 操控、在屏幕顶部提示用户、请求用户协助并读取回执", "phone_begin_ui", "phone_end_ui", "phone_disconnect", "phone_notify_user", "phone_get_user_reply");
        Capability("在 RemoteTool 文件交换目录上传、下载和列出文件，支持大文件分块传输", "phone_transfer_file");
        Capability("通过已授权的 Shizuku 或 Root 执行手机 Shell 命令", "phone_shell");
        Capability("安装、卸载、强停、清除数据、启用或禁用应用", "phone_manage_app");
        Capability("读写系统设置，以及剪贴板、音量、亮度、闪光灯、振动、位置、传感器、网络信息和发送短信等原生能力", "phone_system_setting", "phone_android");
        Capability("调用 Android 组件与广播，查询或修改联系人、日历、短信、媒体等内容提供程序", "phone_intent", "phone_content");
        Capability("读写、复制、移动或删除手机文件；读取通知及执行通知操作、快捷回复", "phone_files", "phone_notifications");
        text.AppendLine(advanced
            ? "本会话绑定已允许高级工具；执行时仍需手机工具页面开启高级操作，并具备相应系统权限。Root、Shizuku、无障碍和通知访问的实际可用性以手机返回的能力信息为准。"
            : "本会话绑定未开放高级工具。请使用上面列出的工具范围；手机端具备 Root 或 Shizuku 并不代表本会话已获准执行高级操作。");
        if (enabled) {
            text.AppendLine("使用流程：");
            text.AppendLine("1. 首次操作先调用 phone_get_capabilities 核对实际能力；以本次返回的设备、权限和可用后端为准，不把连接成功当成操作成功。");
            if (names.Contains("phone_begin_ui") && names.Contains("phone_end_ui")) text.AppendLine("开始查看或操作界面前，先调用 phone_begin_ui，用简短 message 告知用户正在做什么；长操作在声明过期前续期。再调用 phone_observe 或 phone_screenshot 查看当前界面，按实际页面操作，避免使用过时坐标。");
            text.AppendLine("2. 以各工具的参数 Schema 为准。界面坐标使用原始屏幕像素；按节点操作时使用最新观察结果中的 snapshot_id 和节点标识，节点标识在 30 秒后失效。界面变化后重新观察，不要沿用旧节点或猜测控件位置。");
            text.AppendLine("3. 操作后重新观察或读取相关状态，核验实际结果；需要等待页面变化时使用 phone_wait。返回的截图可用于视觉判断，文字和结构化结果可用于状态判断。");
            if (names.Contains("phone_notify_user") && names.Contains("phone_get_user_reply")) text.AppendLine("需要用户解锁、重开应用或其他协助时，用 phone_notify_user(kind=help) 明确请求，保存 notice_id，再用 phone_get_user_reply 核对回执；pending、过期、取消或超时都不代表用户完成。在收到回复前不要继续依赖该操作的步骤。普通提示使用 kind=info。");
            text.AppendLine("4. 遇到 phone_offline、权限不足或后端不可用时，根据返回原因说明当前阻碍。delivery_unknown 表示操作可能已经执行，禁止自动重发同一操作；先重新观察或查询状态核对结果。");
            text.AppendLine("5. 工具调用已由 RemoteTool 处理连接与认证。不要向用户索要内部令牌，也不要将执行结果不明或失败的操作描述为已完成。");
            if (names.Contains("phone_end_ui")) text.AppendLine("6. 界面操作完成先调用 phone_end_ui，结束 UI 声明；它不会断开连接。后续若仍需文件、命令或结果核验，可继续使用已开放工具。");
            if (names.Contains("phone_disconnect")) text.AppendLine("7. 所有手机操作、截图核验及可选完成提示都结束后，必须最后调用 phone_disconnect，立即撤下本 Agent 的连接／操作提示并取消其待处理消息。即使任务受阻、失败或提前结束也需清理。之后不要再调用手机工具、发完成通知或重新 initialize，否则会重新连接；ping 和工具发现不会重新连接。不要为了验证断开再调用手机截图工具。");
        }
        return text.ToString().TrimEnd();
    }
    private static readonly object fileLock = new();
    private async Task EnsureHermesPlugin(CancellationToken ct) {
        await installLock.WaitAsync(ct);
        try {
            var profiles = new HermesProfiles(configuration); var source = configuration["Hermes:SourceDirectory"] ?? Path.Combine(profiles.Root, "hermes-agent");
            var python = configuration["Hermes:PythonExecutable"] ?? Path.Combine(source, "venv", OperatingSystem.IsWindows() ? "Scripts/python.exe" : "bin/python");
            var shipped = Path.Combine(AppContext.BaseDirectory, "phone-tools");
            if (!File.Exists(python) || !File.Exists(Path.Combine(shipped, "install_hermes.py"))) throw new InvalidOperationException("Hermes 手机工具适配器尚未随服务安装");
            var job = Path.Combine(profiles.Root, "cache", "remotetool-phone-install", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(job);
            var resultPath = Path.Combine(job, "result.json");
            var arguments = new[] { Path.Combine(shipped, "install_hermes.py"), "--home", profiles.Home, "--source", source, "--result", resultPath };
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(50));
            Process? process = null;
            Task<string>? output = null, errors = null;
            try {
                // Hermes authenticates its control pipe by user SID. IIS LocalSystem must not
                // install as itself and then try to control the desktop user's gateway.
                if (OperatingSystem.IsWindows() && System.Security.Principal.WindowsIdentity.GetCurrent().IsSystem) {
                    var command = string.Join(" ", new[] { python }.Concat(arguments).Select(QuotePathOrArgument));
                    var launched = RemoteControl.InteractiveProcessLauncher.LaunchInInteractiveSession(command, source);
                    if (!launched.Success) throw new InvalidOperationException("无法在 Hermes 用户环境中加载手机工具，请确认电脑用户已登录");
                    try { process = Process.GetProcessById(launched.ProcessId); }
                    catch (ArgumentException) when (File.Exists(resultPath)) { }
                } else {
                    var info = new ProcessStartInfo(python) { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = source, RedirectStandardOutput = true, RedirectStandardError = true };
                    foreach (var argument in arguments) info.ArgumentList.Add(argument);
                    info.Environment["PYTHONIOENCODING"] = "utf-8";
                    process = Process.Start(info)!;
                    output = process.StandardOutput.ReadToEndAsync(); errors = process.StandardError.ReadToEndAsync();
                }
                if (process != null) await process.WaitForExitAsync(timeout.Token);
                if (output != null) await output;
                if (errors != null) await errors;
                var result = File.Exists(resultPath) ? JsonNode.Parse(await File.ReadAllTextAsync(resultPath, ct))?.AsObject() : null;
                if (result?["ready"]?.GetValue<bool>() != true) {
                    logger.LogWarning("Hermes phone plugin activation failed for Profile {Profile}: {Error} {Message}", HermesProfileScope.Name, result?["error"]?.ToString(), result?["message"]?.ToString());
                    throw new InvalidOperationException("Hermes 手机工具加载失败，网关未确认工具注册；请检查服务端日志");
                }
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
                throw new InvalidOperationException("Hermes 手机工具加载超时，请稍后重试");
            } finally {
                if (process != null) {
                    try { if (!process.HasExited) { process.Kill(true); await process.WaitForExitAsync(); } }
                    finally { process.Dispose(); }
                }
                try {
                    foreach (var file in new[] { resultPath, Path.ChangeExtension(resultPath, ".tmp") }) if (File.Exists(file)) File.Delete(file);
                    Directory.Delete(job);
                } catch (Exception cleanup) when (cleanup is IOException or UnauthorizedAccessException) {
                    logger.LogWarning(cleanup, "Unable to clean Hermes phone plugin activation job");
                }
            }
        } finally { installLock.Release(); }
    }
    private static string QuotePathOrArgument(string value) {
        if (value.IndexOfAny(['"', '\r', '\n']) >= 0) throw new InvalidOperationException("Hermes 安装路径无效");
        return "\"" + value + new string('\\', value.Reverse().TakeWhile(c => c == '\\').Count()) + "\"";
    }
}
