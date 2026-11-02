using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Updates;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/server-update")]
public sealed class ServerUpdateController(ServerUpdateService updates, CodexBridge codex) : ControllerBase
{
    [HttpGet] public IActionResult Status() { Response.Headers.CacheControl = "no-store"; return Ok(updates.Status()); }
    [HttpGet("jobs/{id}")] public IActionResult Job(string id) { Response.Headers.CacheControl = "no-store"; var job = updates.ReadJob(id); return job == null ? NotFound() : Ok(job); }
    [HttpPost("check")] public async Task<IActionResult> Check(CancellationToken ct) => Ok(await updates.Check(ct));
    [HttpPut("settings")] public async Task<IActionResult> Settings(UpdatePolicy policy, CancellationToken ct) {
        try { return Ok(await updates.SavePolicy(policy, ct)); } catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }
    public sealed record InstallRequest(long Sequence, bool Force = false);
    public sealed record RebuildRequest(bool Force = false, bool PullSource = false);
    [HttpPost("rebuild")] public async Task<IActionResult> Rebuild(RebuildRequest request, CancellationToken ct) {
        try { return Accepted(await updates.Install(0, ct, rebuild: true, force: request.Force, pullSource: request.PullSource)); }
        catch (Exception error) when (error is InvalidOperationException or IOException) { return Conflict(new { message = error.Message }); }
    }
    [HttpPost("install")] public async Task<IActionResult> Install(InstallRequest request, CancellationToken ct) {
        try { return Accepted(await updates.Install(request.Sequence, ct, force: request.Force)); }
        catch (Exception error) when (error is InvalidOperationException or IOException) { return Conflict(new { message = error.Message }); }
    }
    [AllowAnonymous, HttpPost("readiness")]
    public async Task<IActionResult> Readiness([FromQuery] string jobId, CancellationToken ct) {
        if (!updates.AuthenticateWorker(Request.Headers["X-RemoteTool-Updater"].ToString())) return Unauthorized();
        try { return Ok(await updates.Readiness(jobId, ct)); } catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }
    [AllowAnonymous, HttpPost("release-drain")]
    public IActionResult ReleaseDrain() {
        if (!updates.AuthenticateWorker(Request.Headers["X-RemoteTool-Updater"].ToString())) return Unauthorized();
        updates.ReleaseDrain(); return Ok();
    }
    [AllowAnonymous, HttpPost("warm-bridge")]
    public async Task<IActionResult> WarmBridge(CancellationToken ct) {
        if (!updates.AuthenticateWorker(Request.Headers["X-RemoteTool-Updater"].ToString())) return Unauthorized();
        using var response = await codex.SendAsync(HttpMethod.Get, "health", null, ct);
        return new ContentResult { StatusCode = (int)response.StatusCode, ContentType = "application/json", Content = await response.Content.ReadAsStringAsync(ct) };
    }
}
