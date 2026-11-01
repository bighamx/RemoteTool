using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.RemoteControl;
namespace RemoteTool.WebApi.Controllers;
[Authorize,ApiController,Route("api/terminal")]
public sealed class TerminalCapabilitiesController:ControllerBase {
    [HttpGet("capabilities")] public object Capabilities()=>TerminalCapabilities.Current();
}
