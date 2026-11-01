using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.Storage;

namespace RemoteTool.WebApi.Controllers;

[Authorize, ApiController, Route("api/storage")]
public sealed class StorageController(ManagedStorageService storage) : ControllerBase
{
    private string Owner => User.FindFirstValue(ClaimTypes.NameIdentifier) ?? User.FindFirstValue("sub") ?? User.Identity?.Name ?? "authenticated";
    [HttpGet("capabilities")] public object Capabilities() => new { supported = true, recycleBin = true, localOnly = false };
    [HttpPost("scan")] public async Task<IActionResult> Scan(CancellationToken ct) => Ok(await Task.Run(() => storage.Scan(Owner, ct), ct));
    [HttpGet("items")] public IActionResult Items(string snapshot, [FromQuery] StorageFilter filter) => Handle(() => storage.Items(Owner, snapshot, filter));
    [HttpPost("prepare")] public IActionResult Prepare(StorageSelection selection) => Handle(() => storage.Prepare(Owner, selection));
    [HttpPost("execute")] public async Task<IActionResult> Execute(StorageExecution request) {
        try { return Ok(await storage.Execute(Owner, request.Plan)); }
        catch (ArgumentException error) { return Conflict(new { message = error.Message }); }
    }
    private IActionResult Handle(Func<object> action) { try { return Ok(action()); } catch (ArgumentException error) { return Conflict(new { message = error.Message }); } }
}
