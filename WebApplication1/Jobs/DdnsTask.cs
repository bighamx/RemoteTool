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
            var candidate = GetBestGlobalIPv6Address();
            if (candidate == null)
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine("未找到有效的 IPv6 地址。");
                context.ResetTextColor();
                return;
            }

            var ipv6 = candidate.Address.ToString();
            context.WriteLine(
                $"选用本机 IPv6 地址：{ipv6}（网卡：{candidate.InterfaceName}，" +
                $"剩余首选寿命：{TimeSpan.FromSeconds(candidate.PreferredLifetime)}）");

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
                    proxied = _settings.Proxied,
                    comment = $"Updated at {DateTime.Now:MM/dd HH:mm:ss}"
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

        private Ipv6Candidate? GetBestGlobalIPv6Address()
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(networkInterface => networkInterface.OperationalStatus == OperationalStatus.Up)
                .SelectMany(networkInterface =>
                {
                    var ipProperties = networkInterface.GetIPProperties();
                    var hasIpv6Gateway = ipProperties.GatewayAddresses.Any(gateway =>
                        gateway.Address.AddressFamily == AddressFamily.InterNetworkV6);

                    return ipProperties.UnicastAddresses
                        .Where(ip => IsGlobalUnicast(ip.Address) &&
                            ip.DuplicateAddressDetectionState == DuplicateAddressDetectionState.Preferred &&
                            ip.SuffixOrigin != SuffixOrigin.Random &&
                            ip.AddressPreferredLifetime > 0)
                        .Select(ip => new Ipv6Candidate(
                            ip.Address,
                            networkInterface.Name,
                            IsPhysicalInterface(networkInterface.NetworkInterfaceType),
                            hasIpv6Gateway,
                            ip.SuffixOrigin,
                            ip.AddressPreferredLifetime,
                            ip.AddressValidLifetime));
                })
                .OrderByDescending(candidate => candidate.IsPhysicalInterface)
                .ThenByDescending(candidate => candidate.HasIpv6Gateway)
                .ThenByDescending(candidate => candidate.SuffixOrigin == SuffixOrigin.LinkLayerAddress)
                .ThenByDescending(candidate => candidate.PreferredLifetime)
                .ThenByDescending(candidate => candidate.ValidLifetime)
                .ThenBy(candidate => candidate.InterfaceName, StringComparer.Ordinal)
                .ThenBy(candidate => candidate.Address.ToString(), StringComparer.Ordinal)
                .FirstOrDefault();
        }

        private static bool IsGlobalUnicast(IPAddress address)
        {
            if (address.AddressFamily != AddressFamily.InterNetworkV6 ||
                IPAddress.IsLoopback(address) ||
                address.IsIPv6LinkLocal ||
                address.IsIPv6SiteLocal)
            {
                return false;
            }

            var bytes = address.GetAddressBytes();
            return bytes.Length == 16 && (bytes[0] & 0xE0) == 0x20; // 2000::/3
        }

        private static bool IsPhysicalInterface(NetworkInterfaceType interfaceType)
        {
            return interfaceType == NetworkInterfaceType.Ethernet ||
                interfaceType == NetworkInterfaceType.GigabitEthernet ||
                interfaceType == NetworkInterfaceType.FastEthernetFx ||
                interfaceType == NetworkInterfaceType.FastEthernetT ||
                interfaceType == NetworkInterfaceType.Wireless80211;
        }

        private sealed class Ipv6Candidate
        {
            public Ipv6Candidate(
                IPAddress address,
                string interfaceName,
                bool isPhysicalInterface,
                bool hasIpv6Gateway,
                SuffixOrigin suffixOrigin,
                long preferredLifetime,
                long validLifetime)
            {
                Address = address;
                InterfaceName = interfaceName;
                IsPhysicalInterface = isPhysicalInterface;
                HasIpv6Gateway = hasIpv6Gateway;
                SuffixOrigin = suffixOrigin;
                PreferredLifetime = preferredLifetime;
                ValidLifetime = validLifetime;
            }

            public IPAddress Address { get; }
            public string InterfaceName { get; }
            public bool IsPhysicalInterface { get; }
            public bool HasIpv6Gateway { get; }
            public SuffixOrigin SuffixOrigin { get; }
            public long PreferredLifetime { get; }
            public long ValidLifetime { get; }
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
