using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace RemoteTool.WebApi.Controllers;

[Authorize(Roles = "Admin")]
public sealed class OidcSettingsController : Controller
{
    [HttpGet("/settings/auth")]
    public IActionResult Index() => View();
}
