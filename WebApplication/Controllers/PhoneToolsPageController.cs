using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
namespace RemoteTool.WebApi.Controllers;

[Authorize]
public sealed class PhoneToolsPageController : Controller {
    [HttpGet("/phone-tools")]
    public IActionResult Index() { ViewBag.Title = "手机工具"; return View("~/Views/PhoneTools/Index.cshtml"); }
}
