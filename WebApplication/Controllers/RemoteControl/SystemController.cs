using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Controllers.RemoteControl;

[ApiController]
[Route("api/[controller]")]
[Authorize]
public class SystemController : ControllerBase
{
    private readonly ISystemControlService _systemService;
    private readonly PerformanceMonitor _performance;

    public SystemController(ISystemControlService systemService, PerformanceMonitor performance)
    {
        _systemService = systemService;
        _performance = performance;
    }

    private IActionResult ApiResult(object? data = null, string? message = null, bool success = true)
        => Ok(new { success, message, data });

    private IActionResult ApiError(string message)
        => BadRequest(new { message });

    [HttpPost("shutdown")]
    public IActionResult Shutdown()
    {
        try
        {
            _systemService.Shutdown();
            return ApiResult(null, OperatingSystem.IsMacOS() ? "关机操作已提交" : "System will shutdown in 5 seconds");
        }
        catch (Exception ex)
        {
            return ApiError($"Shutdown failed: {ex.Message}");
        }
    }

    [HttpPost("reboot")]
    public IActionResult Reboot()
    {
        try
        {
            _systemService.Reboot();
            return ApiResult(null, OperatingSystem.IsMacOS() ? "重启操作已提交" : "System will reboot in 5 seconds");
        }
        catch (Exception ex)
        {
            return ApiError($"Reboot failed: {ex.Message}");
        }
    }

    [HttpPost("cancel-shutdown")]
    public IActionResult CancelShutdown()
    {
        try
        {
            _systemService.CancelShutdown();
            return ApiResult(null, "Shutdown cancelled");
        }
        catch (Exception ex)
        {
            return ApiError($"Cancel shutdown failed: {ex.Message}");
        }
    }

    [HttpPost("sleep")]
    public IActionResult Sleep()
    {
        try
        {
            _systemService.Sleep();
            return ApiResult(null, "System is sleeping");
        }
        catch (Exception ex)
        {
            return ApiError($"Sleep failed: {ex.Message}");
        }
    }

    [HttpPost("screen-off")]
    public async Task<IActionResult> ScreenOff()
    {
        try {
            await _systemService.ScreenOffAsync();
            return ApiResult(null, "已发送息屏请求");
        }
        catch (PlatformNotSupportedException ex) { return StatusCode(501, new { message = ex.Message }); }
        catch (Exception ex) { return ApiError($"息屏失败：{ex.Message}"); }
    }

    [HttpPost("hibernate")]
    public IActionResult Hibernate()
    {
        try
        {
            _systemService.Hibernate();
            return ApiResult(null, "System is hibernating");
        }
        catch (Exception ex)
        {
            return ApiError($"Hibernate failed: {ex.Message}");
        }
    }

    [HttpGet("processes")]
    public async Task<IActionResult> GetProcesses()
    {
        try
        {
            var processes = await _systemService.GetProcessListAsync();
            return ApiResult(processes, "Process list fetched");
        }
        catch (Exception ex)
        {
            return ApiError($"Get processes failed: {ex.Message}");
        }
    }

    [HttpPost("kill/{processId}")]
    public IActionResult KillProcess(int processId)
    {
        try
        {
            var success = _systemService.KillProcess(processId);
            if (success)
                return ApiResult(null, $"Process {processId} terminated");
            return ApiResult(null, $"Failed to terminate process {processId}", false);
        }
        catch (Exception ex)
        {
            return ApiError($"Kill process failed: {ex.Message}");
        }
    }

    [HttpPost("lock")]
    public IActionResult LockWorkstation()
    {
        try
        {
            _systemService.LockWorkstation();
            return ApiResult(null, "Workstation locked");
        }
        catch (Exception ex)
        {
            return ApiError($"Lock workstation failed: {ex.Message}");
        }
    }

    [HttpGet("screenshot")]
    public async Task<IActionResult> GetScreenshot()
    {
        try
        {
            var imageBytes = await _systemService.CaptureScreenAsync();
            Response.Headers.CacheControl = "no-store";
            if (imageBytes.Length == 0)
                return ApiResult(null, "Failed to capture screenshot", false);
            return File(imageBytes, "image/jpeg");
        }
        catch (Exception ex)
        {
            return ApiError($"Screenshot failed: {ex.Message}");
        }
    }

    [HttpGet("info")]
    public IActionResult GetSystemInfo()
    {
        try
        {
            var info = _systemService.GetSystemInfo();
            return ApiResult(info, "System info fetched");
        }
        catch (Exception ex)
        {
            return ApiError($"Get system info failed: {ex.Message}");
        }
    }

    [HttpGet("performance")]
    public IActionResult Performance([FromQuery] int seconds = 60) => ApiResult(_performance.Snapshot(seconds));
}
