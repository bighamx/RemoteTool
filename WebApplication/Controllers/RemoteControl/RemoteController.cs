using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Authorization;

namespace ChuckieHelper.WebApi.Controllers.RemoteControl
{
    [Authorize]
    public class RemoteController : Controller
    {
        public IActionResult Index()
        {
            return View("Remote");
        }
    }
}
