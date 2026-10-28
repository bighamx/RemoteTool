using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/platform")]
public sealed class PlatformController(MacDesktopBridge desktop) : ControllerBase
{
    [HttpGet("capabilities")]
    public async Task<IActionResult> Capabilities(CancellationToken ct) {
        object permissions = null; string error = null;
        if (OperatingSystem.IsMacOS() && desktop.Configured) {
            try { permissions = await desktop.CallAsync(new { type = "status" }, ct); }
            catch (Exception e) when (e is IOException or InvalidOperationException or OperationCanceledException or System.ComponentModel.Win32Exception) { error = e.Message; }
        }
        return Ok(new { platform = OperatingSystem.IsMacOS() ? "macOS" : OperatingSystem.IsWindows() ? "Windows" : "Linux",
            desktopConfigured = OperatingSystem.IsWindows() || OperatingSystem.IsMacOS() && desktop.Configured,
            desktopFormat = OperatingSystem.IsWindows() || OperatingSystem.IsMacOS() ? "mp4" : null,
            desktopNote = OperatingSystem.IsMacOS() ? "支持多显示器、H.264 和 JPEG 回退；Mac 锁屏后需先在电脑上解锁" : null,
            permissions, error, terminal = "line", desktopCollaboration = OperatingSystem.IsWindows() || OperatingSystem.IsMacOS() });
    }
}
