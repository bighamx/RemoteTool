using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace ChuckieHelper.Lib.Tool
{
    public class QBittorrent
    {
        private readonly string baseUrl;
        private readonly string username;
        private readonly string password;
        private readonly HttpClient client;
        private readonly HttpClientHandler handler;

        public QBittorrent(string baseUrl, string username, string password)
        {
            this.baseUrl = baseUrl.TrimEnd('/');
            this.username = username;
            this.password = password;

            handler = new HttpClientHandler
            {
                CookieContainer = new CookieContainer(),
                UseCookies = true,
                UseProxy = false
            };
            client = new HttpClient(handler) { BaseAddress = new Uri(this.baseUrl) };
        }

        /// 登录
        public async Task<bool> LoginAsync()
        {
            var loginContent = new FormUrlEncodedContent(new[]
            {
                new KeyValuePair<string, string>("username", username),
                new KeyValuePair<string, string>("password", password)
            });

            var response = await client.PostAsync("/api/v2/auth/login", loginContent);
            if (!response.IsSuccessStatusCode)
            {
                return false;
            }
            ;

            string result = await response.Content.ReadAsStringAsync();
            return !result.Contains("Fails", StringComparison.OrdinalIgnoreCase);
        }

        /// 获取所有任务
        public async Task<List<Torrent>> GetTorrentsAsync()
        {
            var response = await client.GetAsync("/api/v2/torrents/info");
            response.EnsureSuccessStatusCode();
            string json = await response.Content.ReadAsStringAsync();
            return JsonConvert.DeserializeObject<List<Torrent>>(json);
        }

        //获取任务中每个文件的状态设置
        public async Task<List<TorrentFile>> GetTorrentFilesAsync(string hash)
        {
            var response = await client.GetAsync($"/api/v2/torrents/files?hash={hash}");
            response.EnsureSuccessStatusCode();
            string json = await response.Content.ReadAsStringAsync();
            return JsonConvert.DeserializeObject<List<TorrentFile>>(json);
        }

        public async Task<bool> StopTorrentAsync(string hash)
        {

            string json = await TorrentActionAsync("stop", hash);
            return true;
        }


        public async Task<bool> StartTorrentAsync(string hash)
        {
            string json = await TorrentActionAsync("start", hash);
            return true;
        }

        public async Task<List<TorrentFile>> GetTorrentPopertiesAsync(string hash)
        {
            var response = await client.GetAsync($"/api/v2/torrents/properties?hash={hash}");
            response.EnsureSuccessStatusCode();
            string json = await response.Content.ReadAsStringAsync();
            return JsonConvert.DeserializeObject<List<TorrentFile>>(json);
        }

        /// 根据 hash 导出种子文件
        public async Task<byte[]> ExportTorrentFileAsync(string hash)
        {
            var response = await client.GetAsync($"/api/v2/torrents/export?hash={hash}");
            response.EnsureSuccessStatusCode();
            return await response.Content.ReadAsByteArrayAsync();
        }

        public async Task<bool> DeleteTorrent(string hash, bool deleteFiles = false)
        {
            var data = new FormUrlEncodedContent(new[]
            {
            new KeyValuePair<string, string>("hashes", hash),
            new KeyValuePair<string, string>("deleteFiles", deleteFiles?"true":"false"),
        });

            var response = await client.PostAsync($"api/v2/torrents/delete", data);
            if (!response.IsSuccessStatusCode) return false;
            string result = await response.Content.ReadAsStringAsync();
            return true;
        }

        public async Task<bool> SetTorrentLocation(string hash, string newPath)
        {
            var data = new FormUrlEncodedContent(new[]
            {
            new KeyValuePair<string, string>("hashes", hash),
            new KeyValuePair<string, string>("location",newPath),
        });

            var response = await client.PostAsync($"api/v2/torrents/setLocation", data);
            if (!response.IsSuccessStatusCode) return false;
            string result = await response.Content.ReadAsStringAsync();
            return true;
        }

        public async Task<string> TorrentActionAsync(string action, string hash)
        {
            var data = new FormUrlEncodedContent(new[]
            {
            new KeyValuePair<string, string>("hashes", hash),
        });

            var response = await client.PostAsync($"api/v2/torrents/{action}", data);
            if (!response.IsSuccessStatusCode) return null;
            string result = await response.Content.ReadAsStringAsync();
            return result;
        }



        /// <summary>
        /// 添加磁力链接任务，可选 skipChecking、tags、priority
        /// </summary>
        public async Task<bool> AddMagnetAsync(
            string magnetLink,
            string savePath,
            bool skipChecking = false,
            string tags = null,
            int priority = -1)
        {
            var form = new MultipartFormDataContent
        {
            { new StringContent(magnetLink), "urls" },
            { new StringContent(savePath), "savepath" },
            { new StringContent(skipChecking ? "true" : "false"), "skip_checking" }
        };

            if (!string.IsNullOrEmpty(tags))
                form.Add(new StringContent(tags), "tags");

            if (priority >= 0)
                form.Add(new StringContent(priority.ToString()), "priority");

            var response = await client.PostAsync("/api/v2/torrents/add", form);
            return response.IsSuccessStatusCode;
        }
        /// <summary>
        /// 添加种子文件任务，可选 skipChecking、tags、priority
        /// </summary>
        public async Task<bool> AddTorrentAsync(
            string torrentFilePath,
            string savePath,
            bool skipChecking = false,
            string tags = null,
            int priority = -1)
        {
            using var form = new MultipartFormDataContent();
            using var fs = File.OpenRead(torrentFilePath);
            form.Add(new StreamContent(fs), "torrents", Path.GetFileName(torrentFilePath));
            form.Add(new StringContent(savePath), "savepath");
            form.Add(new StringContent(skipChecking ? "true" : "false"), "skip_checking");

            if (!string.IsNullOrEmpty(tags))
                form.Add(new StringContent(tags), "tags");

            if (priority >= 0)
                form.Add(new StringContent(priority.ToString()), "priority");

            var response = await client.PostAsync("/api/v2/torrents/add", form);
            return response.IsSuccessStatusCode;
        }

        public async Task<bool> AddTorrentAsync(
            byte[] torrent,
            string savePath,
            bool skipChecking = false,
            string name = null,
            string tags = null,
            int priority = -1,
            string contentLayout = null)
        {
            using var form = new MultipartFormDataContent();

            form.Add(new ByteArrayContent(torrent), "torrents", name ?? "Torrent");
            form.Add(new StringContent(savePath), "savepath");
            form.Add(new StringContent(skipChecking ? "true" : "false"), "skip_checking");

            if (!string.IsNullOrEmpty(tags))
                form.Add(new StringContent(tags), "tags");

            if (priority >= 0)
                form.Add(new StringContent(priority.ToString()), "priority");

            if (!string.IsNullOrWhiteSpace(contentLayout))
                form.Add(new StringContent(contentLayout), "contentLayout");

            var response = await client.PostAsync("/api/v2/torrents/add", form);
            return response.IsSuccessStatusCode;
        }



        public async Task SetFileSkipAsync(string torrentHash, List<int> fileIndex)
        {
            var form = new FormUrlEncodedContent(new[]
            {
            new KeyValuePair<string, string>("hash", torrentHash),
            new KeyValuePair<string, string>("id", string.Join('|',fileIndex)),
            new KeyValuePair<string, string>("priority", "0")
        });

            var response = await client.PostAsync("/api/v2/torrents/filePrio", form);
            response.EnsureSuccessStatusCode();
        }
        /// <summary>
        /// 删除任务
        /// </summary>
        /// <param name="hash">任务 hash 或多个用 | 分隔</param>
        /// <param name="deleteFiles">是否删除数据文件</param>
        public async Task<bool> DeleteTorrentAsync(string hash, bool deleteFiles = false)
        {
            var form = new FormUrlEncodedContent(new[]
            {
            new KeyValuePair<string, string>("hashes", hash),
            new KeyValuePair<string, string>("deleteFiles", deleteFiles ? "true" : "false")
        });

            var response = await client.PostAsync("/api/v2/torrents/delete", form);
            return response.IsSuccessStatusCode;
        }



    }
    public class TorrentFile
    {
        public int index { get; set; }
        public string name { get; set; }
        public long size { get; set; }
        public double progress { get; set; }
        public int priority { get; set; }  // 0=不下载,1=低,6=高
    }


    public class Torrent
    {
        public int added_on { get; set; }
        public long amount_left { get; set; }
        public bool auto_tmm { get; set; }
        public float availability { get; set; }
        public string category { get; set; }
        public string comment { get; set; }
        public long completed { get; set; }
        public long completion_on { get; set; }

        public int dl_limit { get; set; }
        public int dlspeed { get; set; }

        public long downloaded { get; set; }
        public long downloaded_session { get; set; }
        public int eta { get; set; }
        public bool f_l_piece_prio { get; set; }
        public bool force_start { get; set; }
        public bool has_metadata { get; set; }
        public string hash { get; set; }
        public int inactive_seeding_time_limit { get; set; }
        public string infohash_v1 { get; set; }
        public string infohash_v2 { get; set; }
        public int last_activity { get; set; }
        public string magnet_uri { get; set; }
        public int max_inactive_seeding_time { get; set; }
        public int max_ratio { get; set; }
        public int max_seeding_time { get; set; }
        public string name { get; set; }
        public int num_complete { get; set; }
        public int num_incomplete { get; set; }
        public int num_leechs { get; set; }
        public int num_seeds { get; set; }
        public decimal popularity { get; set; }
        public int priority { get; set; }
        public bool _private { get; set; }
        public float progress { get; set; }
        public decimal ratio { get; set; }
        public int ratio_limit { get; set; }
        public int reannounce { get; set; }

        public int seeding_time { get; set; }
        public int seeding_time_limit { get; set; }
        public int seen_complete { get; set; }
        public bool seq_dl { get; set; }
        public long size { get; set; }
        public string state { get; set; }
        public bool super_seeding { get; set; }
        public string tags { get; set; }
        public int time_active { get; set; }
        public long total_size { get; set; }
        public string tracker { get; set; }
        public int trackers_count { get; set; }
        public int up_limit { get; set; }
        public long uploaded { get; set; }
        public long uploaded_session { get; set; }
        public int upspeed { get; set; }

        public string content_path { get; set; }
        public string root_path { get; set; }
        public string save_path { get; set; }
        public string download_path { get; set; }

        /// <summary>
        /// 当前内容布局是否没有根目录。单文件种子和 NoSubfolder 多文件种子都可能为 true。
        /// </summary>
        public bool HasNoRootDirectory => string.IsNullOrEmpty(root_path);

        // 保留旧属性，避免影响现有调用方；不能用它判断种子是否只有一个文件。
        public bool NoDir => HasNoRootDirectory;

        /// <summary>
        /// 种子内是文件夹,且文件夹内只有一个文件
        /// </summary>
        public bool IsSingleFileInDir => !HasNoRootDirectory && root_path != content_path;
    }


    public class TorrentInfo
    {
        public string Hash { get; set; }
        public string Name { get; set; }
        public string SavePath { get; set; }
        public List<int> IgnoreFiles { get; set; }
    }
}
