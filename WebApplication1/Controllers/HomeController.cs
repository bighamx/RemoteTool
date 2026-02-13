using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;
using System.Diagnostics;
using WebApplication1.Models;

namespace WebApplication1.Controllers
{
    [Authorize]
    public class HomeController : Controller
    {
        private readonly ILogger<HomeController> _logger;

        public HomeController(ILogger<HomeController> logger)
        {
            _logger = logger;
        }


        public IActionResult Index()
        {
            return View();
        }

        public IActionResult Wrapper(string url, string title = "Remote Tool")
        {
            // 防止开放重定向：仅允许相对路径或同站 URL
            if (!string.IsNullOrEmpty(url) && (url.Contains("://") || url.StartsWith("//")))
            {
                return BadRequest("不允许嵌入外部 URL");
            }

            ViewBag.TargetUrl = url;
            ViewBag.Title = title;
            return View();
        }

        [Route("/hangfire")]
        public IActionResult Hangfire()
        {
            return View();
        }


        
        [AllowAnonymous]
        [ResponseCache(Duration = 0, Location = ResponseCacheLocation.None, NoStore = true)]
        public IActionResult Error()
        {
            return View(new ErrorViewModel { RequestId = Activity.Current?.Id ?? HttpContext.TraceIdentifier });
        }
    }
}
