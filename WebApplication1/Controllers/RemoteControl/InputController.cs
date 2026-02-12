using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services.RemoteControl;

namespace ChuckieHelper.WebApi.Controllers.RemoteControl;

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

    [HttpPost("click")]
    public IActionResult Click([FromBody] ClickRequest request)
    {
        if (request.X < 0 || request.X > 1 || request.Y < 0 || request.Y > 1)
            return ApiError("Invalid coordinates");

        _systemService.SendMouseClick(request.X, request.Y);
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
}

public class ClipboardRequest
{
    public string? Text { get; set; }
}

public class ClickRequest
{
    public double X { get; set; }
    public double Y { get; set; }
}

public class MouseEventRequest
{
    public double X { get; set; }
    public double Y { get; set; }
    public int Button { get; set; } = 0; // 0=左键, 1=中键, 2=右键
}

/// <summary>滚轮请求：归一化坐标 + 滚动量（正=上滚，负=下滚）</summary>
public class MouseWheelRequest
{
    public double X { get; set; }
    public double Y { get; set; }
    public int Delta { get; set; } // 如 ±120 表示一档
}

public class KeyboardRequest
{
    public int VkCode { get; set; }
    public bool IsKeyDown { get; set; }
}

public class KeyboardMultiRequest
{
    public int[] VkCodes { get; set; } = Array.Empty<int>();
    public bool IsKeyDown { get; set; }
}

