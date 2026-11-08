using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Controllers.RemoteControl;

[ApiController, Authorize, Route("api/files/bookmarks")]
public sealed class FileBookmarksController(FileBookmarkStore bookmarks) : ControllerBase
{
    [HttpGet]
    public IActionResult List() => Ok(bookmarks.Read(User));

    [HttpPost]
    public IActionResult Add([FromBody] FileBookmarkRequest request)
    {
        try { return Ok(bookmarks.Add(User, request.Path)); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
        catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }

    [HttpDelete]
    public IActionResult Remove([FromQuery] string path)
    {
        try { return Ok(bookmarks.Remove(User, path)); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }
}

public sealed record FileBookmarkRequest(string Path);
