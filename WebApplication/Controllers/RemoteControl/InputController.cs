using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.RemoteControl;
using RemoteTool.WebApi.Models.RemoteControl;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Controllers.RemoteControl;

[ApiController]
[Route("api/[controller]")]
[Authorize]
public class InputController : ControllerBase
    
{
    private readonly SystemService _systemService;

    private IActionResult ApiError(string message)
        => BadRequest(new { message });
    public InputController(SystemService systemService)
    {
        _systemService = systemService;
    }

    [HttpPost("text")]
    [HttpPost("mouse-relative")]
    [HttpPost("pointer-click")]
    [HttpPost("pointer-wheel")]
    public async Task<IActionResult> NativeInput([FromBody] System.Text.Json.JsonElement request)
    {
        var kind = Request.Path.Value!.Split('/').Last();
        var text = request.TryGetProperty("text", out var t) ? t.GetString() ?? "" : "";
        if (text.Length > 2048) return ApiError("Text is too long");
        int Value(string name) => request.TryGetProperty(name, out var p) ? p.GetInt32() : 0;
        if (InteractiveProcessLauncher.IsRunningInSession0)
            await DesktopAgent.SendNativeInputAsync(kind, text, Value("dx"), Value("dy"), Value("button"));
        else await InputDesktopDispatcher.RunAsync(() => { NativeDesktopInput.Execute(kind,text,Value("dx"),Value("dy"),Value("button"));return Array.Empty<byte>(); });
        return Ok(new { message = "Input sent" });
    }

    [HttpPost("click")]
    public async Task<IActionResult> Click([FromBody] ClickRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        if (InteractiveProcessLauncher.IsRunningInSession0) await DesktopAgent.SendMouseClickAsync(request.X,request.Y);
        else _systemService.SendMouseClick(request.X, request.Y);
        return Ok(new { message = "Click sent" });
    }

    [HttpPost("right-click")]
    public IActionResult RightClick([FromBody] ClickRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseRightClick(request.X, request.Y);
        return Ok(new { message = "Right click sent" });
    }

    [HttpPost("middle-click")]
    public IActionResult MiddleClick([FromBody] ClickRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseMiddleClick(request.X, request.Y);
        return Ok(new { message = "Middle click sent" });
    }

    [HttpPost("keyboard")]
    public IActionResult Keyboard([FromBody] KeyboardRequest request)
    {
        if (request.VkCode < 0 || request.VkCode > 255)
            return ApiError("Invalid virtual key code");

        _systemService.SendKeyboardEvent((byte)request.VkCode, request.IsKeyDown);
        return Ok(new { message = "Keyboard event sent" });
    }

    [HttpPost("keyboard-multi")]
    public IActionResult KeyboardMulti([FromBody] KeyboardMultiRequest request)
    {
        if (request.VkCodes == null || request.VkCodes.Length == 0)
            return ApiError("No key codes provided");

        var vkCodes = request.VkCodes.Select(k => (byte)k).ToArray();
        _systemService.SendKeyboardEvents(vkCodes, request.IsKeyDown);
        return Ok(new { message = "Multi-key event sent" });
    }

    [HttpPost("mouse-down")]
    public IActionResult MouseDown([FromBody] MouseEventRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseDown(request.X, request.Y, request.Button);
        return Ok(new { message = "Mouse down sent" });
    }

    [HttpPost("mouse-up")]
    public IActionResult MouseUp([FromBody] MouseEventRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseUp(request.X, request.Y, request.Button);
        return Ok(new { message = "Mouse up sent" });
    }

    [HttpPost("mouse-move")]
    public IActionResult MouseMove([FromBody] ClickRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseMove(request.X, request.Y);
        return Ok(new { message = "Mouse move sent" });
    }

    /// <summary>鼠标滚轮：归一化坐标 + 滚动量（正=向上滚，负=向下滚，单位同 WHEEL_DELTA 120）</summary>
    [HttpPost("mouse-wheel")]
    public IActionResult MouseWheel([FromBody] MouseWheelRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseWheel(request.X, request.Y, request.Delta);
        return Ok(new { message = "Mouse wheel sent" });
    }

    /// <summary>获取远程剪贴板文本（用于 Ctrl+C 同步到本地）</summary>
    [HttpGet("clipboard")]
    public IActionResult GetClipboard()
    {
        var text = _systemService.GetClipboardText();
        return Ok(new { text });
    }

    /// <summary>设置远程剪贴板文本（用于 Ctrl+V 从本地同步到远程）</summary>
    [HttpPost("clipboard")]
    public IActionResult SetClipboard([FromBody] ClipboardRequest request)
    {
        _systemService.SetClipboardText(request.Text ?? "");
        return Ok(new { message = "Clipboard set" });
    }

    /// <summary>
    /// 输入链路体检：分别计时「唤醒+查询」「键盘」「鼠标移动」各若干次，用于定位输入延迟在哪一段。
    /// 只读诊断，不改桌面状态（键盘仅发一次 A 按下，鼠标移动仅在同一点重复）。
    /// </summary>
    [HttpGet("_diag")]
    public async Task<IActionResult> Diagnose([FromQuery] int n = 5)
    {
        if (!InteractiveProcessLauncher.IsRunningInSession0)
            return Ok(new { session0 = false, message = "本进程不在 Session 0，输入走本进程直连路径" });
        n = Math.Clamp(n, 1, 20);
        async Task<List<double>> Measure(Func<Task> action)
        {
            var list = new List<double>();
            for (int i = 0; i < n; i++)
            {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                try { await action(); } catch { /* 计时仍然有效 */ }
                list.Add(sw.Elapsed.TotalMilliseconds);
            }
            return list;
        }
        var ensure = await Measure(() => DesktopAgent.EnsureAgentRunningAsync());
        var keyboard = await Measure(() => DesktopAgent.SendKeyboardEventAsync(0x41, true));
        var move = await Measure(() => DesktopAgent.SendMouseMoveAsync(0.5, 0.5));
        var state = await Measure(async () => { await DesktopAgent.GetSessionStateAsync(); });

        object Stat(List<double> v)
        {
            var s = v.OrderBy(x => x).ToList();
            return new { min = Math.Round(s[0], 1), p50 = Math.Round(s[s.Count / 2], 1), max = Math.Round(s[^1], 1) };
        }
        return Ok(new
        {
            ensureAgent = Stat(ensure),
            keyboard = Stat(keyboard),
            mouseMove = Stat(move),
            sessionState = Stat(state)
        });
    }

    /// <summary>查询目标会话的输入桌面状态：desktop=Default 已解锁 / Winlogon 锁屏或登录界面。
    /// 同时带上交互会话的真实屏幕尺寸（代理上报，DPI 感知）——前端据此把「原生（零拷贝）」选项
    /// 标成实际分辨率，任何分辨率的机器都自适应，不写死 4K。</summary>
    [HttpGet("session-state")]
    public async Task<IActionResult> SessionState()
    {
        var size = await DesktopAgent.GetScreenSizeAsync();

        if (InteractiveProcessLauncher.IsRunningInSession0)
        {
            var state = await DesktopAgent.GetSessionStateAsync();
            if (state == null) return Ok(new { available = false, message = "桌面代理不可用" });
            return Ok(new
            {
                available = true,
                desktop = state.Value.Desktop,
                locked = state.Value.Locked,
                width = size?.Width ?? 0,
                height = size?.Height ?? 0
            });
        }

        var desktop = InputDesktopDispatcher.CurrentDesktopName;
        return Ok(new
        {
            available = true,
            desktop,
            locked = !string.Equals(desktop, "Default", StringComparison.OrdinalIgnoreCase),
            width = size?.Width ?? 0,
            height = size?.Height ?? 0
        });
    }

    /// <summary>
    /// 远程解锁：把口令注入登录界面（锁屏时的 Winlogon 安全桌面）并回车。
    /// 仅在本机已锁屏时有效；密码只经内存转发，不写日志。
    /// </summary>
    [HttpPost("unlock")]
    public async Task<IActionResult> Unlock([FromBody] UnlockRequest request)
    {
        var password = request.Password ?? "";
        if (password.Length == 0) return ApiError("密码不能为空");
        if (password.Length > 256) return ApiError("密码过长");

        if (InteractiveProcessLauncher.IsRunningInSession0)
        {
            var (ok, error) = await DesktopAgent.UnlockWithPasswordAsync(password);
            if (!ok) return StatusCode(500, new { message = error ?? "解锁失败" });
        }
        else
        {
            try
            {
                var result = await InputDesktopDispatcher.RunAsync(() =>
                {
                    var (succeeded, err) = DesktopAgent.UnlockOnCurrentDesktop(password);
                    return Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { ok = succeeded, error = err }));
                });
                using var doc = System.Text.Json.JsonDocument.Parse(result);
                if (!doc.RootElement.GetProperty("ok").GetBoolean())
                    return StatusCode(500, new { message = doc.RootElement.GetProperty("error").GetString() });
            }
            catch (Exception ex)
            {
                return StatusCode(500, new { message = ex.Message });
            }
        }

        // 等待登录界面处理，然后回报实际结果（成功与否用桌面名说话）
        await Task.Delay(2500);
        var after = await SessionStateCoreAsync();
        return Ok(new { message = "已提交口令", desktop = after.Desktop, locked = after.Locked });
    }

    private static async Task<(string Desktop, bool Locked)> SessionStateCoreAsync()
    {
        if (InteractiveProcessLauncher.IsRunningInSession0)
        {
            var state = await DesktopAgent.GetSessionStateAsync();
            if (state != null) return (state.Value.Desktop, state.Value.Locked);
        }
        var desktop = InputDesktopDispatcher.CurrentDesktopName;
        return (desktop, !string.Equals(desktop, "Default", StringComparison.OrdinalIgnoreCase));
    }
}



