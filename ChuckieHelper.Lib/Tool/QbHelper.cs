using ChuckieHelper.Lib.Tool;
using Newtonsoft.Json;

namespace ChuckieHelper.Lib
{
    public static class QbHelper
    {
        public static async Task<bool> Maintence(this QBittorrent qb, Action<string> log = null)
        {
            log ??= Console.WriteLine;

            if (!await qb.LoginAsync())
            {
                log("QB 登录失败!");
                return false;
            }


            var torrents = await qb.GetTorrentsAsync();
            foreach (var torrent in torrents)
            {
                if (torrent.tracker.Contains("acg") || torrent.tracker.Contains("moe"))
                {
                    await qb.DeleteTorrent(torrent.hash);
                    log($"已删除: {torrent.name}");
                    continue;
                }

                string safeName = SanitizeFileName(torrent.name);
                //处理单个文件被下载到媒体库根文件夹的情况
                var libDir = new[] { "TV", "Movie", "JAV", "Bangumi", "H-Anima", "Animation" };
                if (torrent.NoDir && libDir.Contains(GetDirName(torrent.save_path)))
                {
                    var dstPath = Path.Combine(torrent.save_path, safeName.Split('.').First());
                    await qb.SetTorrentLocation(torrent.hash, dstPath);
                    log($"{torrent.name}  {torrent.save_path} >> {dstPath}");
                }
            }
            return true;
        }

        public static async Task<bool> Move(this QBittorrent qbSrc, QBittorrent qbDst, Action<string> log = null)
        {
            log ??= Console.WriteLine;
            var homeDocker = qbSrc;
            var home = qbDst;
            if (!await homeDocker.LoginAsync())
            {
                log("QB1 登录失败!");
                return false;
            }

            if (!await home.LoginAsync())
            {
                log("QB2 登录失败!");
                return false;
            }
            var torrents = await homeDocker.GetTorrentsAsync();
            foreach (var torrent in torrents)
            {
                if (torrent.tracker.Contains("acg") || torrent.tracker.Contains("moe"))
                {
                    await homeDocker.DeleteTorrent(torrent.hash);
                    log($"已删除: {torrent.name}");
                    continue;
                }
                //停止现有任务
                await homeDocker.StopTorrentAsync(torrent.hash);
                //请求种子文件
                var torrentBytes = await homeDocker.ExportTorrentFileAsync(torrent.hash);

                var ignoreFiles = homeDocker.GetTorrentFilesAsync(torrent.hash).Result.Where(x => x.priority == 0).Select(x => x.index).ToList();


                //转移到另一个qb
                string dstPath = torrent.save_path.StartsWith("//")
                        ? ConvertWindowsPathToLinux(torrent.save_path)
                        : ConvertLinuxPathToWindows(torrent.save_path);

                string safeName = SanitizeFileName(torrent.name);


                var done = torrent.progress >= 1;
                bool added = await home.AddTorrentAsync(torrentBytes, dstPath, done, safeName);
                if (added)
                {
                    await homeDocker.DeleteTorrent(torrent.hash);
                }
                if (added && ignoreFiles.Any())
                {
                    await home.SetFileSkipAsync(torrent.hash, ignoreFiles);
                }
                if (torrent.progress > 0 && torrent.progress < 1)
                {
                    //重新开始校验和下载
                    //await home.TorrentActionAsync("recheck", torrent.hash);
                    await home.StartTorrentAsync(torrent.hash);
                }
                //处理单个文件被下载到媒体库根文件夹的情况
                var libDir = new[] { "TV", "Movie", "JAV", "Bagumi", "H-Anima" };
                if (torrent.NoDir && libDir.Contains(GetDirName(dstPath)))
                {

                    dstPath = Path.Combine(torrent.save_path, safeName.Split('.').First());
                    await home.SetTorrentLocation(torrent.hash, dstPath);
                    await home.StartTorrentAsync(torrent.hash);
                    log($"{torrent.name}  {torrent.save_path} >> {dstPath}");
                }
                log($"已转移: {torrent.name}");
            }
            return true;
        }

        static string GetDirName(string path)
        {
            return path.Split('/', '\\').Last();
        }
        public static async Task Export(this QBittorrent qb, string exportDir, Action<string> log = null)
        {
            log ??= Console.WriteLine;
            if (!await qb.LoginAsync())
            {
                log("登录失败!");
                return;
            }
            log("登录成功!");

            // 获取所有任务
            var torrents = await qb.GetTorrentsAsync();

            // 创建保存目录

            Directory.CreateDirectory(exportDir);

            foreach (var torrent in torrents)
            {
                log($"处理任务: {torrent.name}");

                // 请求种子文件
                var torrentFileResponse = await qb.ExportTorrentFileAsync(torrent.hash);
                // 保存 .torrent 文件	
                string safeName = SanitizeFileName(torrent.name);
                string torrentFilePath = Path.Combine(exportDir, $"{safeName}.torrent");
                await File.WriteAllBytesAsync(torrentFilePath, torrentFileResponse);
                //文件列表优先级
                var ignoreFiles = qb.GetTorrentFilesAsync(torrent.hash).Result.Where(x => x.priority == 0).Select(x => x.index).ToList();
                // 保存任务信息 JSON
                var info = new TorrentInfo
                {
                    Hash = torrent.hash,
                    Name = torrent.name,
                    SavePath = torrent.save_path,
                    IgnoreFiles = ignoreFiles
                };
                string jsonPath = Path.Combine(exportDir, $"{safeName}.json");
                string json = JsonConvert.SerializeObject(info);
                await File.WriteAllTextAsync(jsonPath, json);

                log($"已保存: {torrentFilePath} 以及 {jsonPath}");
            }
        }
        public static async Task Import(this QBittorrent qb, string exportDir, Action<string> log = null)
        {
            log ??= Console.WriteLine;

            if (!await qb.LoginAsync())
            {
                log("登录失败!");
                return;
            }
            log("登录成功!");


            if (!Directory.Exists(exportDir))
            {
                log("ExportedTorrents 文件夹不存在!");
                return;
            }

            var torrentFiles = Directory.GetFiles(exportDir, "*.torrent");
            foreach (var torrentFile in torrentFiles)
            {
                string baseName = Path.GetFileNameWithoutExtension(torrentFile);
                string jsonPath = Path.Combine(exportDir, baseName + ".json");
                if (!File.Exists(jsonPath))
                {
                    log($"未找到 JSON 文件: {jsonPath}");
                    continue;
                }

                // 读取 json 文件
                var jsonText = await File.ReadAllTextAsync(jsonPath);
                var info = JsonConvert.DeserializeObject<TorrentInfo>(jsonText);

                // 替换 Windows 路径为 Linux 格式
                string linuxPath = info.SavePath.StartsWith("//")
                ? ConvertWindowsPathToLinux(info.SavePath)
                : ConvertLinuxPathToWindows(info.SavePath);

                // 添加任务
                bool added = await qb.AddTorrentAsync(torrentFile, linuxPath, true);
                if (added && info.IgnoreFiles.Any())
                {
                    await qb.SetFileSkipAsync(info.Hash, info.IgnoreFiles);
                }
                log(added
                    ? $"已添加任务: {info.Name} -> {linuxPath}"
                    : $"添加失败: {info.Name}");
            }
        }

        public static string ConvertWindowsPathToLinux(string winPath)
        {
            if (string.IsNullOrEmpty(winPath)) return winPath;

            // 检测驱动盘符，如 E:\ 转为 /mnt/E
            if (winPath.Length > 2 && winPath[1] == ':' && winPath[2] == '\\')
            {
                string drive = winPath[0].ToString().ToUpper();
                string pathRest = winPath.Substring(2).Replace("\\", "/");
                return $"/mnt/{drive}{pathRest}";
            }

            // 否则直接替换 \ 为 /
            return winPath.Replace("\\", "/");
        }

        public static string ConvertLinuxPathToWindows(string linuxPath)
        {
            var drv = linuxPath[5];
            var p = $"{drv}:\\" + linuxPath.Replace("/mnt/", "").Substring(2).Replace("/", "\\");
            return p;
        }


        public static string SanitizeFileName(string name)
        {
            foreach (var c in Path.GetInvalidFileNameChars())
            {
                name = name.Replace(c, '_');
            }
            return name;
        }

        public static async Task CleanUnwantedFiles(this QBittorrent qb, Func<string, Task<bool>> deleteFileFunc, Action<string> log = null)
        {
            log ??= Console.WriteLine;
            if (!await qb.LoginAsync())
            {
                log("登录失败!");
                return;
            }

            var torrents = await qb.GetTorrentsAsync();
            // 过滤未完成的任务
            var unfinished = torrents.Where(t => t.progress < 1).ToList();

            log($"发现 {unfinished.Count} 个未完成任务，开始检查文件...");

            foreach (var torrent in unfinished)
            {
                try
                {
                    // 获取文件列表
                    var files = await qb.GetTorrentFilesAsync(torrent.hash);
                    // 过滤设置为不下载的文件 (priority == 0)
                    var unwantedFiles = files.Where(f => f.priority == 0).ToList();

                    if (unwantedFiles.Any())
                    {
                        string savePath = torrent.save_path;
                        string localBasePath = savePath;
                        if (savePath.StartsWith("/mnt/"))
                        {
                            localBasePath = ConvertLinuxPathToWindows(savePath);
                        }

                        foreach (var file in unwantedFiles)
                        {
                            string relativePath = file.name.Replace('/', Path.DirectorySeparatorChar);
                            string fullPath = Path.Combine(localBasePath, relativePath);

                            bool deleted = false;
                            try
                            {
                                deleted = await deleteFileFunc(fullPath);
                                log(deleted ? $"已删除 unwanted 文件: {fullPath}" : $"未删除: {fullPath}");
                            }
                            catch (Exception ex)
                            {
                                log($"删除失败 {fullPath}: {ex.Message}");
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    log($"处理任务 {torrent.name} 出错: {ex.Message}");
                }
            }
            log("清理完成。");
        }
    }
}
