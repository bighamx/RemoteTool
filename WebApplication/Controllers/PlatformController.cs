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
            desktopFormat = OperatingSystem.IsWindows() ? "mp4" : OperatingSystem.IsMacOS() ? "jpeg" : null,
            desktopNote = OperatingSystem.IsMacOS() ? "Mac 第一版使用 JPEG 帧；仅支持当前登录用户的主屏幕，尚未接入 H.264" : null,
            permissions, error, terminal = "line", desktopCollaboration = OperatingSystem.IsWindows() });
    }
}
