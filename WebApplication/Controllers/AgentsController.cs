using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace RemoteTool.WebApi.Controllers;

[Authorize]
[Route("agents")]
public sealed class AgentsController : Controller
{
    [HttpGet("codex")]
    public IActionResult Codex() { ViewBag.Agent = "codex"; ViewBag.Title = "Codex"; return View("Index"); }

    [HttpGet("hermes")]
    public IActionResult Hermes() { ViewBag.Agent = "hermes"; ViewBag.Title = "Hermes"; return View("Index"); }
}
