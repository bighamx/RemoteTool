using ChuckieHelper.WebApi.Models;
using Hangfire;
using Hangfire.Console;
using Hangfire.Server;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ChuckieHelper.WebApi.Jobs
{
    public class EmbyTask(IOptionsMonitor<EmbySettings> options, IHttpClientFactory httpClientFactory)
    {
        private EmbySettings Settings => options.CurrentValue;

        public async Task<bool> MaintainMediaLibrary(PerformContext context)
        {
            context.WriteLine("Starting Emby Media Library Maintenance...");
            var url = Settings.Url?.TrimEnd('/');
            var token = Settings.Token;

            if (string.IsNullOrWhiteSpace(url) || string.IsNullOrWhiteSpace(token))
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine("Emby configuration is missing (Url or Token).");
                context.ResetTextColor();
                return false;
            }

            try
            {
                var client = httpClientFactory.CreateClient("Emby");

                // 1. Get virtual folders
                string virtualFoldersUrl = $"{url}/emby/Library/VirtualFolders?api_key={token}";
                var response = await client.GetAsync(virtualFoldersUrl);

                if (!response.IsSuccessStatusCode)
                {
                    context.SetTextColor(ConsoleTextColor.Red);
                    context.WriteLine($"Failed to get virtual folders from Emby. Status: {response.StatusCode}");
                    context.ResetTextColor();
                    return false;
                }

                var content = await response.Content.ReadAsStringAsync();
                var virtualFolders = JsonSerializer.Deserialize<List<VirtualFolderDto>>(content, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });

                if (virtualFolders == null || virtualFolders.Count == 0)
                {
                    context.WriteLine("No media libraries found.");
                    return true;
                }

                bool hasMovedAnyFile = false;

                // 2. Iterate libraries and locations
                foreach (var folder in virtualFolders)
                {
                    context.WriteLine($"Checking library: {folder.Name}");
                    if (folder.Locations == null || folder.Locations.Count == 0)
                    {
                        continue;
                    }

                    foreach (var location in folder.Locations)
                    {
                        if (!Directory.Exists(location))
                        {
                            context.WriteLine($"Directory does not exist, skipping: {location}");
                            continue;
                        }

                        // Get independent files in the root of the location and sort by length to process primary videos before their extras/trailers
                        var files = Directory.GetFiles(location, "*.*", SearchOption.TopDirectoryOnly)
                                             .OrderBy(f => f.Length)
                                             .ToArray();
                        
                        foreach (var file in files)
                        {
                            if (!File.Exists(file)) continue; // Skip if already moved as a related file

                            if (IsVideoFile(file))
                            {
                                string baseName = Path.GetFileNameWithoutExtension(file);
                                string newFolder = Path.Combine(location, baseName);

                                if (!Directory.Exists(newFolder))
                                {
                                    Directory.CreateDirectory(newFolder);
                                }

                                // 找到所有同名或带前后缀包含关系的关联文件（字幕、nfo、图片、Extras视频等）
                                var relatedFiles = files.Where(f => 
                                    File.Exists(f) && IsRelatedFile(f, baseName)
                                ).ToList();

                                foreach (var relatedFile in relatedFiles)
                                {
                                    string destFile = Path.Combine(newFolder, Path.GetFileName(relatedFile));
                                    
                                    try 
                                    {
                                        File.Move(relatedFile, destFile);
                                        context.WriteLine($"Moved: {Path.GetFileName(relatedFile)} -> {newFolder}");
                                        hasMovedAnyFile = true;
                                    }
                                    catch (Exception ex)
                                    {
                                        context.SetTextColor(ConsoleTextColor.Red);
                                        context.WriteLine($"Failed to move {Path.GetFileName(relatedFile)}: {ex.Message}");
                                        context.ResetTextColor();
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Refresh library if anything was moved
                if (hasMovedAnyFile)
                {
                    context.WriteLine("Files were moved. Firing library scan API...");
                    string refreshUrl = $"{url}/emby/Library/Refresh?api_key={token}";
                    var refreshResponse = await client.PostAsync(refreshUrl, null);

                    if (refreshResponse.IsSuccessStatusCode)
                    {
                        context.SetTextColor(ConsoleTextColor.Green);
                        context.WriteLine("Library scan API called successfully.");
                    }
                    else
                    {
                        context.SetTextColor(ConsoleTextColor.Red);
                        context.WriteLine($"Failed to call Library scan API. Status: {refreshResponse.StatusCode}");
                    }
                    context.ResetTextColor();
                }
                else
                {
                    context.WriteLine("No independent video files found. No scan needed.");
                }

                return true;
            }
            catch (Exception ex)
            {
                context.SetTextColor(ConsoleTextColor.Red);
                context.WriteLine($"Error during Emby maintenance: {ex.Message}");
                context.ResetTextColor();
                return false;
            }
        }

        private bool IsRelatedFile(string filePath, string baseName)
        {
            string fileNameWithoutExt = Path.GetFileNameWithoutExtension(filePath);
            
            // 完全同名 (比如 Avatar.mp4, Avatar.nfo)
            if (fileNameWithoutExt.Equals(baseName, StringComparison.OrdinalIgnoreCase))
                return true;

            // 具有标准分隔符包含关系的 (比如 Avatar.zh.srt, Avatar-poster.jpg, Avatar_fanart.png)
            if (fileNameWithoutExt.StartsWith(baseName + ".", StringComparison.OrdinalIgnoreCase))
                return true;
            if (fileNameWithoutExt.StartsWith(baseName + "-", StringComparison.OrdinalIgnoreCase))
                return true;
            if (fileNameWithoutExt.StartsWith(baseName + "_", StringComparison.OrdinalIgnoreCase))
                return true;

            return false;
        }

        private bool IsVideoFile(string filePath)
        {
            var ext = Path.GetExtension(filePath).ToLowerInvariant();
            var videoExtensions = Settings.VideoExtensions;
            if (videoExtensions == null || videoExtensions.Count == 0)
            {
                videoExtensions = new List<string> { ".mp4", ".mkv", ".avi", ".rmvb", ".wmv", ".ts", ".iso", ".mov", ".flv", ".webm" };
            }

            return videoExtensions.Contains(ext);
        }

        private class VirtualFolderDto
        {
            public string Name { get; set; }
            public List<string> Locations { get; set; }
        }
    }

    public static class EmbyTaskExtensions
    {
        public static IServiceCollection AddEmbyTask(this IServiceCollection services)
        {
            services.AddOptions<EmbySettings>()
                .BindConfiguration("EmbySettings");
            services.TryAddTransient<EmbyTask>();
            // 确保 HttpClientFactory 被注册，如果没有注册过的话
            services.AddHttpClient();
            return services;
        }

        public static IHost UseHangfireEmbyTask(this IHost app)
        {
            // Register as RecurringJob
            RecurringJob.AddOrUpdate<EmbyTask>(
                "emby-maintenance",
                x => x.MaintainMediaLibrary(null),
                Cron.Daily,
                new RecurringJobOptions { TimeZone = TimeZoneInfo.Local });

            return app;
        }
    }
}
