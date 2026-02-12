using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services.RemoteControl;

namespace ChuckieHelper.WebApi.Controllers.RemoteControl;

[ApiController]
[Route("api/[controller]")]
[Authorize]
public class SystemController : ControllerBase
{
    private readonly ISystemControlService _systemService;

    public SystemController(ISystemControlService systemService)
    {
        _systemService = systemService;
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
            return ApiResult(null, "System will shutdown in 5 seconds");
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
            return ApiResult(null, "System will reboot in 5 seconds");
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
    public IActionResult GetScreenshot()
    {
        try
        {
            var imageBytes = _systemService.CaptureScreen();
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
}
