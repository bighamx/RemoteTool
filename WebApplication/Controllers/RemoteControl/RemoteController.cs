using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Authorization;

namespace RemoteTool.WebApi.Controllers.RemoteControl
{
    [Authorize]
    public class RemoteController : Controller
    {
        [HttpGet("/api/remote/capabilities")]
        public IActionResult Capabilities() => Ok(RemoteTool.WebApi.Services.RemoteControl.RemoteDesktopPlatform.Capabilities());
        public IActionResult Index()
        {
            return View("Remote");
        }
    }
}
