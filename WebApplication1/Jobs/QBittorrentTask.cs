using ChuckieHelper.Lib;
using ChuckieHelper.Lib.Tool;
using ChuckieHelper.WebApi.Models;
using Hangfire;
using Hangfire.Console;
using Hangfire.Server;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace ChuckieHelper.WebApi.Jobs
{
    public class QBittorrentTask
    {
        private readonly IOptionsMonitor<QbSettings> _qbSettingsMonitor;
        private readonly Services.RemoteControl.FileService _fileService;

        public QBittorrentTask(IOptionsMonitor<QbSettings> options, Services.RemoteControl.FileService fileService)
        {
            _qbSettingsMonitor = options;
            _fileService = fileService;
        }

        private QbSettings _qbSettings => _qbSettingsMonitor.CurrentValue;

        public async Task<bool> Maintence(PerformContext context, string qbUrl = null)
        {
            context.WriteLine("Starting Maintence...");
            var defaultQbHomeUrl = _qbSettings.DefaultHomeUrl;
            qbUrl = string.IsNullOrWhiteSpace(qbUrl) ? defaultQbHomeUrl : qbUrl;

            context.WriteLine($"Target URL: {MaskUrl(qbUrl)}");

            var (apiUrl, user, pass) = qbUrl.ParseApiUrl();
            var qb = new QBittorrent(apiUrl, user, pass);
            var r = await qb.Maintence(context.WriteLine);

            if (r)
            {
                context.SetTextColor(ConsoleTextColor.Green);
                context.WriteLine($"[QBittorrentTask] Maintence for {qbUrl}: OK");
                context.ResetTextColor();
            }
            else
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine($"[QBittorrentTask] Maintence for {qbUrl}: Fail");
                context.ResetTextColor();
            }

            return r;
        }

        public async Task<bool> Move(PerformContext context, string qbUrl = null, string qbUrl2 = null)
        {
            context.WriteLine("Starting Move Task...");
            var defaultQbDockerUrl = _qbSettings.DefaultDockerUrl;
            var defaultQbHomeUrl = _qbSettings.DefaultHomeUrl;

            qbUrl = string.IsNullOrWhiteSpace(qbUrl) ? defaultQbDockerUrl : qbUrl;
            qbUrl2 = string.IsNullOrWhiteSpace(qbUrl2) ? defaultQbHomeUrl : qbUrl2;

            context.WriteLine($"Source: {MaskUrl(qbUrl)}");
            context.WriteLine($"Dest: {MaskUrl(qbUrl2)}");

            var (apiUrl, user, pass) = qbUrl.ParseApiUrl();
            var qb = new QBittorrent(apiUrl, user, pass);

            var (apiUrl2, user2, pass2) = qbUrl2.ParseApiUrl();
            var qb2 = new QBittorrent(apiUrl2, user2, pass2);

            var r = await qb.Move(qb2, context.WriteLine);
            await qb2.Maintence(context.WriteLine); // Also run maintenance on the target

            if (r)
            {
                context.SetTextColor(ConsoleTextColor.Green);
                context.WriteLine($"[QBittorrentTask] Move from {qbUrl} to {qbUrl2}: OK");
                context.ResetTextColor();
            }
            else
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine($"[QBittorrentTask] Move from {qbUrl} to {qbUrl2}: Fail");
                context.ResetTextColor();
            }

            return r;
        }

        public async Task CleanUnwantedFiles(PerformContext context, string qbUrl = null)
        {
            context.WriteLine("Starting CleanUnwantedFiles...");
            var defaultQbHomeUrl = _qbSettings.DefaultHomeUrl;
            qbUrl = string.IsNullOrWhiteSpace(qbUrl) ? defaultQbHomeUrl : qbUrl;

            context.WriteLine($"Target URL: {MaskUrl(qbUrl)}");

            var (apiUrl, user, pass) = qbUrl.ParseApiUrl();
            var qb = new QBittorrent(apiUrl, user, pass);


            Task<bool> deleteFileFunc(string path)
            {
                bool fileDeleted = _fileService.DeleteFile(path);

                var dir = Path.GetDirectoryName(path);
                bool dirDeleted = false;
                if (Directory.Exists(dir) && !Directory.EnumerateFileSystemEntries(dir).Any())
                {
                    dirDeleted = _fileService.DeleteDirectory(dir);
                }
                return Task.FromResult(fileDeleted || dirDeleted);
            }
            await qb.CleanUnwantedFiles(deleteFileFunc, context.WriteLine);

            context.SetTextColor(ConsoleTextColor.Green);
            context.WriteLine($"[QBittorrentTask] CleanUnwantedFiles for {qbUrl}: Done");
            context.ResetTextColor();
        }

        /// <summary>
        /// 脱敏 URL 中的用户名和密码（http://user:pass@host → http://***:***@host）
        /// </summary>
        private static string MaskUrl(string url)
        {
            try
            {
                var uri = new Uri(url);
                if (!string.IsNullOrEmpty(uri.UserInfo))
                    return url.Replace(uri.UserInfo, "***:***");
            }
            catch { }
            return url;
        }
    }

    public static class QBittorrentTaskExtensions
    {
        public static IServiceCollection AddQBittorrentTask(this IServiceCollection services)
        {
            services.AddOptions<QbSettings>()
                .BindConfiguration("QbSettings");
            services.TryAddTransient<QBittorrentTask>();
            return services;
        }

        public static IHost UseHangfireQBittorrentTask(this IHost app)
        {
            // Example recurring job for Maintenance (e.g., every hour)
            RecurringJob.AddOrUpdate<QBittorrentTask>(
                "qb-maintenance",
                x => x.Maintence(null, null),
                Cron.Hourly,
                new RecurringJobOptions { TimeZone = TimeZoneInfo.Local });

            // Example recurring job for Move (e.g., every day at 2am)
            RecurringJob.AddOrUpdate<QBittorrentTask>(
                "qb-move",
                x => x.Move(null, null, null),
                Cron.Hourly,
                new RecurringJobOptions { TimeZone = TimeZoneInfo.Local });

            // Recurring job for CleanUnwantedFiles (e.g., daily at 3am)
            RecurringJob.AddOrUpdate<QBittorrentTask>(
                "qb-clean-unwanted",
                x => x.CleanUnwantedFiles(null, null),
                Cron.Daily,
                new RecurringJobOptions { TimeZone = TimeZoneInfo.Local });

            return app;
        }
    }
}
