using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;

namespace RemoteTool.WebApi.Services.RemoteControl;

public static class RemoteDesktopPlatform
{
    public static bool Supported => OperatingSystem.IsWindows() || OperatingSystem.IsMacOS();
    public static string Platform => OperatingSystem.IsWindows() ? "Windows" : OperatingSystem.IsMacOS() ? "macOS" : OperatingSystem.IsLinux() ? "Linux" : "Unknown";
    public static string Message => Supported ? "" : $"当前 {Platform} 服务端尚不支持远程桌面，可使用终端、文件管理和 Agent 会话。";
    public static object Capabilities() => new { supported = Supported, platform = Platform, message = Message };
    public static object Rejection() => new { code = "remote_desktop_unsupported", message = Message };
}

/// <summary>Reject unsupported desktop operations before capture/input resources are created.</summary>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Method)]
public sealed class RemoteDesktopOnlyAttribute : ActionFilterAttribute
{
    public override void OnActionExecuting(ActionExecutingContext context)
    {
        // Existing clients use this read-only endpoint to discover desktop readiness.
        if (context.ActionDescriptor.RouteValues.TryGetValue("action",out var action) && action == "SessionState") return;
        if (!RemoteDesktopPlatform.Supported)
            context.Result = new ObjectResult(RemoteDesktopPlatform.Rejection()) { StatusCode = 501 };
    }
}
