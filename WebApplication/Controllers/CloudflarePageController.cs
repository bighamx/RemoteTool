using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace RemoteTool.WebApi.Controllers;

[Authorize]
public sealed class CloudflarePageController : Controller {
    [HttpGet("/cloudflare")]
    public IActionResult Index() { ViewBag.Title = "Cloudflare"; return View("~/Views/Cloudflare/Index.cshtml"); }
}
