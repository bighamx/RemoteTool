using Microsoft.AspNetCore.Mvc;

namespace ChuckieHelper.WebApi.Controllers;

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
        Response.Cookies.Delete("access_token");
        Response.Headers.CacheControl = "no-store, no-cache, must-revalidate";
        Response.Headers.Pragma = "no-cache";
        Response.Headers.Expires = "0";
        return View();
    }
}
