using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.ViewFeatures;

namespace RemoteTool.WebApi.Controllers;

[ApiController, AllowAnonymous, Route("api/app-start")]
public sealed class AppStartController(IFileVersionProvider versions) : ControllerBase
{
    // Public interface resources only. Never return or cache authenticated page data here.
    [HttpGet, ResponseCache(NoStore = true)]
    public IActionResult Get() => Ok(new { assets = new[] {
        "/css/site.css", "/helper/lib/viewuiplus.css", "/css/shell.css", "/css/mobile.css", "/css/server-update.css",
        "/helper/lib/vue.global.prod.js", "/helper/lib/viewuiplus.min.js", "/js/web-api.js", "/js/index.js",
        "/js/site.js", "/js/mobile-navigation.js", "/js/server-update.js", "/js/workspace-router.js"
    }.Select(path => versions.AddFileVersionToPath(Request.PathBase, path)).ToArray() });
}
