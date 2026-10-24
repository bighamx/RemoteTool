using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/hermes/admin"), Route("api/hermes/profiles/{profile}/admin"), ServiceFilter(typeof(HermesProfileFilter))]
public sealed class HermesAdminController(HermesAdminFiles files, HermesGatewayAdmin gateway) : ControllerBase
{
    [HttpGet("files")] public IActionResult Files() => FileResult(files.List);
    [HttpGet("files/{id}")] public IActionResult Read(string id) => FileResult(() => files.Read(id));
    [HttpPost("files/{id}")] [RequestSizeLimit(2 * 1024 * 1024)]
    public IActionResult Save(string id, [FromBody] JsonObject body) => FileResult(() => files.Save(id,
        body["revision"]?.ToString() ?? "", body["content"]?.ToString() ?? "", body["overwrite"]?.GetValue<bool>() == true, body["backup_id"]?.ToString()));
    private IActionResult FileResult(Func<JsonObject> action) {
        Response.Headers.CacheControl = "no-store";
        try { return Ok(action()); }
        catch (AdminFileConflict) { return Conflict(new { message = "文件已被其他客户端修改，编辑内容已保留", code = "file_changed" }); }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or IOException or UnauthorizedAccessException) { return BadRequest(new { message = error.Message }); }
    }
    [HttpGet("gateway")] public async Task<IActionResult> Status(CancellationToken ct) { Response.Headers.CacheControl = "no-store"; return Ok(await gateway.Status(ct)); }
    [HttpGet("gateway/log")] public async Task<IActionResult> Log([FromQuery] long offset = 0, [FromQuery] string key = "", CancellationToken ct = default) { Response.Headers.CacheControl = "no-store"; return Ok(await gateway.Log(offset, key, ct)); }
    [HttpPost("gateway/update")] public async Task<IActionResult> Update(CancellationToken ct) {
        try { return Ok(await gateway.Start(true, ct, "update")); }
        catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }
    [HttpPost("gateway/restart")] public async Task<IActionResult> Restart([FromBody] JsonObject body, CancellationToken ct) {
        try { return Ok(await gateway.Start(body["wait"]?.GetValue<bool>() == true, ct)); }
        catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }
    [HttpPost("gateway/cancel")] public async Task<IActionResult> Cancel(CancellationToken ct) {
        try { return Ok(await gateway.Cancel(ct)); } catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }
}
