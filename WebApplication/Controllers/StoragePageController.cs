using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
namespace RemoteTool.WebApi.Controllers;
[Authorize]
public sealed class StoragePageController : Controller {
    [HttpGet("/storage")] public IActionResult Index() { ViewBag.Title = "服务端空间管理"; return View("~/Views/Storage/Index.cshtml"); }
}
