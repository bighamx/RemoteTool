using RemoteTool.WebApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace RemoteTool.WebApi.Controllers;

[ApiController]
[Route("api/device")]
public sealed class DeviceController : ControllerBase
{
    private readonly DeviceIdentity _identity;
    public DeviceController(DeviceIdentity identity) => _identity = identity;

    // Deliberately public so the Android app can verify an endpoint before sending credentials.
    [HttpGet("identity")]
    [ResponseCache(NoStore = true)]
    public IActionResult Get() => Ok(new { id = _identity.Id, protocolVersion = 1 });
}
