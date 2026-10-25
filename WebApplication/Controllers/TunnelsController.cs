using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.Tunnels;

namespace RemoteTool.WebApi.Controllers;

[Authorize]
public sealed class TunnelsController(TunnelGateway tunnels) : Controller
{
    [HttpGet("/Tunnels")] public IActionResult Index() => View();
    [HttpGet("/api/tunnels")] public IActionResult Status() { Response.Headers.CacheControl = "no-store"; return Ok(new { data = tunnels.Status() }); }
    [HttpPost("/api/tunnels/{id}/stop")] public async Task<IActionResult> Stop(string id) => await tunnels.Stop(id) ? Ok(new { ok = true }) : NotFound();
}
