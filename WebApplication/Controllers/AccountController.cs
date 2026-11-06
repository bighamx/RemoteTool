using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services;

namespace RemoteTool.WebApi.Controllers;

public class AccountController : Controller
{
    [HttpGet]
    public IActionResult Login()
    {
        return View();
    }

    /// <summary>登出：删除 Cookie 并返回清除本地 token 的页面，确保远程控制等依赖 localStorage 的功能一并登出。</summary>
    [HttpGet]
    public IActionResult Logout()
    {
        AuthCookies.Delete(Response, Request.IsHttps);
        Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
        Response.Headers.Pragma = "no-cache";
        Response.Headers.Expires = "0";
        return View();
    }
}
