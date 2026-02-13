using ChuckieHelper.Lib;
using ChuckieHelper.Lib.Tool;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;
using ChuckieHelper.WebApi.Models;
using ChuckieHelper.WebApi.Jobs;
using Newtonsoft.Json;

namespace ChuckieHelper.WebApi.Controllers
{
    [Authorize]
    public class QbController : Controller
    {
        private readonly IOptionsMonitor<QbSettings> _qbSettingsMonitor;
        private readonly QBittorrentTask _qbTask;

        public QbController(IOptionsMonitor<QbSettings> options, QBittorrentTask qbTask)
        {
            _qbSettingsMonitor = options;
            _qbTask = qbTask;
        }

        private QbSettings _qbSettings => _qbSettingsMonitor.CurrentValue;

        public async Task<IActionResult> Maintence(string qbUrl)
        {
            try
            {
                var r = await _qbTask.Maintence(null, qbUrl);
                return Content(r ? "OK" : "Fail");
            }
            catch (Exception e)
            {
                return Content(e.Message);
            }
        }

        public IActionResult Config()
        {
            try
            {
                return Content(JsonConvert.SerializeObject(_qbSettings, Formatting.Indented));
            }
            catch (Exception e)
            {
                return Content(e.Message);
            }
        }

        public async Task<IActionResult> Move(string qbUrl, string qbUrl2)
        {
            try
            {
                var r = await _qbTask.Move(null, qbUrl, qbUrl2);
                return Content(r ? "OK" : "Fail");
            }
            catch (Exception e)
            {
                return Content(e.Message);
            }

        }
    }
}
