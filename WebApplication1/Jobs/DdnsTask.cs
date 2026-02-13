using ChuckieHelper.WebApi.Models;
using Hangfire;
using Hangfire.Console;
using Hangfire.Server;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Options;
using System.Net;
using System.Net.Http.Headers;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Newtonsoft.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace ChuckieHelper.WebApi.Jobs
{
    public class DdnsTask
    {
        private readonly IOptionsMonitor<CloudflareSettings> _settingsMonitor;
        private readonly IHttpClientFactory _httpClientFactory;

        public DdnsTask(IOptionsMonitor<CloudflareSettings> options, IHttpClientFactory httpClientFactory)
        {
            _settingsMonitor = options;
            _httpClientFactory = httpClientFactory;
        }

        private CloudflareSettings _settings => _settingsMonitor.CurrentValue;

        public async Task Execute(PerformContext context)
        {
            var ipv6 = GetGlobalIPv6Address();
            if (string.IsNullOrEmpty(ipv6))
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine("未找到有效的 IPv6 地址。");
                context.ResetTextColor();
                return;
            }

            // check current
            var client = _httpClientFactory.CreateClient();
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", _settings.ApiToken);

            var recordUrl = $"https://api.cloudflare.com/client/v4/zones/{_settings.ZoneId}/dns_records?type=AAAA&name={_settings.RecordName}";
            var response = await client.GetAsync(recordUrl);

            if (!response.IsSuccessStatusCode)
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine($"获取 DNS 记录失败: {response.StatusCode}");
                context.ResetTextColor();
                return;
            }

            var content = await response.Content.ReadAsStringAsync();
            dynamic json = JsonConvert.DeserializeObject(content);

            if (json.success != true || json.result.Count == 0)
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine($"未找到名称为 {_settings.RecordName} 的 AAAA 记录。");
                context.ResetTextColor();
                return;
            }

            string recordId = json.result[0].id;
            string currentIp = json.result[0].content;

            if (currentIp != ipv6)
            {
                context.WriteLine($"检测到 IPv6 地址变更：{currentIp} -> {ipv6}，正在更新 DNS 记录...");

                var updateBody = new
                {
                    type = "AAAA",
                    name = _settings.RecordName,
                    content = ipv6,
                    ttl = _settings.Ttl,
                    proxied = _settings.Proxied
                };

                var updateUrl = $"https://api.cloudflare.com/client/v4/zones/{_settings.ZoneId}/dns_records/{recordId}";
                var putContent = new StringContent(JsonConvert.SerializeObject(updateBody), Encoding.UTF8, "application/json");

                var updateResponse = await client.PutAsync(updateUrl, putContent);
                var updateResString = await updateResponse.Content.ReadAsStringAsync();
                dynamic updateJson = JsonConvert.DeserializeObject(updateResString);

                if (updateJson.success == true)
                {
                    context.SetTextColor(ConsoleTextColor.Green);
                    context.WriteLine($"DNS 记录更新成功：{_settings.RecordName} -> {ipv6}");
                    context.ResetTextColor();
                }
                else
                {
                    context.SetTextColor(ConsoleTextColor.Red);
                    context.WriteLine($"更新 DNS 记录失败：{updateJson.errors}");
                    context.ResetTextColor();
                }
            }
            else
            {
                context.WriteLine("IPv6 地址未变更，无需更新。");
            }
        }

        private string? GetGlobalIPv6Address()
        {
            foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (networkInterface.OperationalStatus != OperationalStatus.Up) continue;

                var ipProperties = networkInterface.GetIPProperties();
                foreach (var ip in ipProperties.UnicastAddresses)
                {
                    if (ip.Address.AddressFamily == AddressFamily.InterNetworkV6 &&
                        !IPAddress.IsLoopback(ip.Address) &&
                        !ip.Address.IsIPv6LinkLocal &&
                        !ip.Address.IsIPv6SiteLocal &&
                        (ip.Address.ToString().StartsWith("2") || ip.Address.ToString().StartsWith("3"))) // Global Unicast start with 2000::/3
                    {
                        // Filter Logic from PowerShell:
                        // AddressState -eq 'Preferred' (Not directly available in .NET Standard easy way without PInvoke or assuming default)
                        // But typically Global IPv6 that is not temporary is what we want.
                        // For simplicity, returning the first Global Unicast IPv6.
                        return ip.Address.ToString();
                    }
                }
            }
            return null;
        }
    }

    public static class DdnsTaskExtensions
    {
        public static IServiceCollection AddDdnsTask(this IServiceCollection services)
        {
            services.AddOptions<CloudflareSettings>()
                .BindConfiguration("CloudflareSettings");
            services.TryAddTransient<DdnsTask>();
            services.AddHttpClient(); // Ensure HttpClient is available
            return services;
        }

        public static IHost UseHangfireDdnsTask(this IHost app)
        {
            RecurringJob.AddOrUpdate<DdnsTask>(
                "ddns-ipv6",
                x => x.Execute(null),
                "*/30 * * * *", // Every 30 minutes
                 new RecurringJobOptions { TimeZone = TimeZoneInfo.Local });
            return app;
        }
    }
}
