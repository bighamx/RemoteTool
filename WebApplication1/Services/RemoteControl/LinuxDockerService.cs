using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Docker.DotNet;
using Docker.DotNet.Models;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

public class LinuxDockerService : IDockerService, IDisposable
{
    private readonly DockerClient _client;

    public LinuxDockerService()
    {
        // 根据操作系统选择 Docker Socket 路径
        var dockerUri = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? new Uri("npipe://./pipe/docker_engine")
            : new Uri("unix:///var/run/docker.sock");

        _client = new DockerClientConfiguration(dockerUri).CreateClient();
    }

    public void Dispose()
    {
        _client?.Dispose();
        GC.SuppressFinalize(this);
    }

    /// <summary>
    /// 获取 Docker 容器列表
    /// </summary>
    public async Task<List<DockerContainer>> GetContainersAsync()
    {
        try
        {
            var containers = await _client.Containers.ListContainersAsync(
                new ContainersListParameters { All = true });

            return containers.Select(c => new DockerContainer
            {
                Id = c.ID.Substring(0, Math.Min(12, c.ID.Length)),
                Names = c.Names.FirstOrDefault()?.TrimStart('/') ?? "",
                Image = c.Image,
                Status = c.Status,
                Ports = string.Join(", ", c.Ports.Select(p => $"{p.PrivatePort}->{p.PublicPort}/{p.Type}")),
                State = c.State
            }).ToList();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetContainers error: {ex.Message}");
            return new List<DockerContainer>();
        }
    }

    /// <summary>
    /// 获取所有运行中容器的资源使用统计（CPU/内存） - SDK 实现较复杂，需流式读取，暂保留简化版或仅列出
    /// 这里简化为返回空列表，因为 SDK 的 GetContainerStatsAsync 是流式的。
    /// 如果必须实现，需要对每个容器启动一个后台任务去读取流，代价较大。
    /// 暂时仅返回空，或者如果 CLI 可用则用 CLI 降级（但目标是去 CLI）。
    /// </summary>
    public async Task<List<ContainerUsageStats>> GetContainerStatsAsync()
    {
        // 简易实现：暂不支持实时 stats，因 SDK 模式为长连接流
        // 若需实现需重构为 WebSocket 推送或类似机制
        return await Task.FromResult(new List<ContainerUsageStats>());
    }

    /// <summary>
    /// 启动容器
    /// </summary>
    public async Task<bool> StartContainerAsync(string containerId)
    {
        try
        {
            var started = await _client.Containers.StartContainerAsync(containerId, new ContainerStartParameters());
            return started;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"StartContainer error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 停止容器
    /// </summary>
    public async Task<bool> StopContainerAsync(string containerId)
    {
        try
        {
            var stopped = await _client.Containers.StopContainerAsync(containerId, new ContainerStopParameters());
            return stopped;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"StopContainer error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 删除容器
    /// </summary>
    public async Task<bool> RemoveContainerAsync(string containerId, bool force = false)
    {
        try
        {
            await _client.Containers.RemoveContainerAsync(containerId, new ContainerRemoveParameters { Force = force });
            return true;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"RemoveContainer error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 获取容器日志
    /// </summary>
    public async Task<string> GetContainerLogsAsync(string containerId, int lines = 100, int timeoutSeconds = 10)
    {
        try
        {
            var stream = await _client.Containers.GetContainerLogsAsync(containerId,
                new ContainerLogsParameters
                {
                    ShowStdout = true,
                    ShowStderr = true,
                    Tail = lines.ToString()
                });

            using var reader = new StreamReader(stream);
            return await reader.ReadToEndAsync();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetContainerLogs error: {ex.Message}");
            return "";
        }
    }

    /// <summary>
    /// 获取 Docker 镜像列表
    /// </summary>
    public async Task<List<DockerImage>> GetImagesAsync()
    {
        try
        {
            var images = await _client.Images.ListImagesAsync(new ImagesListParameters { All = true });
            return images.Select(i => new DockerImage
            {
                Id = i.ID.Replace("sha256:", "").Substring(0, 12),
                Repository = i.RepoTags.FirstOrDefault()?.Split(':')[0] ?? "<none>",
                Tag = i.RepoTags.FirstOrDefault()?.Split(':').ElementAtOrDefault(1) ?? "<none>",
                Size = (i.Size / 1024 / 1024).ToString() + " MB",
                Created = i.Created.ToString()
            }).ToList();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetImages error: {ex.Message}");
            return new List<DockerImage>();
        }
    }

    /// <summary>
    /// 拉取镜像更新
    /// </summary>
    public async Task<bool> PullImageAsync(string imageTag)
    {
        try
        {
            var parts = imageTag.Split(':');
            var image = parts[0];
            var tag = parts.Length > 1 ? parts[1] : "latest";

            await _client.Images.CreateImageAsync(
                new ImagesCreateParameters { FromImage = image, Tag = tag },
                null,
                new Progress<JSONMessage>(m => 
                {
                    if (m != null && !string.IsNullOrEmpty(m.Status))
                    {
                        Console.WriteLine(m.Status);
                    }
                }));
            return true;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"PullImage error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 检查镜像是否有新版本（简化版：重新拉取）
    /// </summary>
    public async Task<bool> CheckImageUpdateAsync(string imageTag)
    {
        return await PullImageAsync(imageTag);
    }

    // --- Docker Compose 部分保持使用 CLI，因为 SDK 不支持 Compose ---

    /// <summary>
    /// 使用 docker-compose 创建和启动容器
    /// </summary>
    public async Task<bool> ComposeUpAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath)) return false;
            var directory = Path.GetDirectoryName(composePath) ?? "";
            var result = await ExecuteDockerComposeCommandAsync("up -d", directory);
            return !string.IsNullOrEmpty(result);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ComposeUp error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 使用 docker-compose 移除容器
    /// </summary>
    public async Task<bool> ComposeDownAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath)) return false;
            var directory = Path.GetDirectoryName(composePath) ?? "";
            var result = await ExecuteDockerComposeCommandAsync("down", directory);
            return !string.IsNullOrEmpty(result);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ComposeDown error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 使用 docker-compose 停止容器
    /// </summary>
    public async Task<bool> ComposeStopAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath)) return false;
            var directory = Path.GetDirectoryName(composePath) ?? "";
            var result = await ExecuteDockerComposeCommandAsync("stop", directory);
            return !string.IsNullOrEmpty(result);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ComposeStop error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 使用 docker-compose 拉取镜像
    /// </summary>
    public async Task<(bool Success, string Output)> ComposePullAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath)) return (false, "Compose 文件不存在");
            var directory = Path.GetDirectoryName(composePath) ?? "";
            var result = await ExecuteDockerComposeCommandAsync("pull", directory);
            return (!string.IsNullOrEmpty(result), result);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ComposePull error: {ex.Message}");
            return (false, ex.Message);
        }
    }

    public async Task<string> ReadComposeFileAsync(string path)
    {
        if (!File.Exists(path)) throw new FileNotFoundException($"File not found: {path}");
        return await File.ReadAllTextAsync(path);
    }

    public async Task<bool> WriteComposeFileAsync(string path, string content)
    {
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("Path cannot be empty");
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory) && !Directory.Exists(directory)) Directory.CreateDirectory(directory);
        await File.WriteAllTextAsync(path, content);
        return true;
    }

    public async Task<(bool isValid, string message)> ValidateComposeFileAsync(string path, string content)
    {
        // 简化验证，不再依赖 CLI config 命令，防止无 CLI 时报错
        // 仅做基本 YAML 结构检查
        return ValidateComposeYamlStructure(content);
    }

    private (bool isValid, string message) ValidateComposeYamlStructure(string content)
    {
        if (string.IsNullOrWhiteSpace(content)) return (false, "Content is empty");
        // 简单检查是否包含 services 关键字
        if (!content.Contains("services:")) return (false, "Missing 'services:' definition");
        return (true, "Basic structure valid");
    }

    public async Task<List<ComposeContainerInfo>> GetContainersByComposeProjectAsync(string projectName)
    {
        // SDK 实现：通过 Label 过滤
        try
        {
            var containers = await _client.Containers.ListContainersAsync(new ContainersListParameters
            {
                All = true,
                Filters = new Dictionary<string, IDictionary<string, bool>>
                {
                    { "label", new Dictionary<string, bool> { { $"com.docker.compose.project={projectName}", true } } }
                }
            });

            return containers.Select(c => new ComposeContainerInfo
            {
                Id = c.ID.Substring(0, 12),
                Names = c.Names.FirstOrDefault() ?? "",
                Image = c.Image,
                Status = c.Status,
                State = c.State
            }).ToList();
        }
        catch
        {
            return new List<ComposeContainerInfo>();
        }
    }

    public async Task<List<ComposeProject>> GetComposeStatusAsync()
    {
        // SDK 无法直接获取 Compose Project 列表（这是 Docker Compose CLI 的功能）
        // 替代方案：列出所有容器，按 com.docker.compose.project 标签聚合
        try
        {
            var containers = await _client.Containers.ListContainersAsync(new ContainersListParameters { All = true });
            var projects = containers
                .Where(c => c.Labels != null && c.Labels.ContainsKey("com.docker.compose.project"))
                .GroupBy(c => c.Labels["com.docker.compose.project"])
                .Select(g => 
                {
                    var firstContainer = g.First();
                    var configFiles = "";
                    if (firstContainer.Labels.TryGetValue("com.docker.compose.project.config_files", out var files))
                    {
                        configFiles = files;
                    }
                    else if (firstContainer.Labels.TryGetValue("com.docker.compose.project.working_dir", out var workingDir))
                    {
                        // Fallback: guess standard filename in working dir
                        configFiles = Path.Combine(workingDir, "docker-compose.yml");
                    }

                    return new ComposeProject
                    {
                        Name = g.Key,
                        Status = g.All(c => c.State == "running") ? "running" : "mixed",
                        ContainerCount = g.Count(),
                        ConfigFiles = configFiles, // Populate ConfigFiles
                        Containers = g.Select(c => new ComposeContainerInfo
                        {
                            Id = c.ID.Substring(0, 12),
                            Names = c.Names.FirstOrDefault() ?? "",
                            Image = c.Image,
                            Status = c.Status,
                            State = c.State
                        }).ToList()
                    };
                }).ToList();

            return projects;
        }
        catch
        {
            return new List<ComposeProject>();
        }
    }

    public async Task<string> GetComposeLogsAsync(int lines = 100)
    {
        try
        {
            // SDK 方式：获取所有带有 com.docker.compose.project 标签的容器日志并合并
            var containers = await _client.Containers.ListContainersAsync(new ContainersListParameters
            {
                All = true,
                Filters = new Dictionary<string, IDictionary<string, bool>>
                {
                    { "label", new Dictionary<string, bool> { { "com.docker.compose.project", true } } }
                }
            });

            var logBuilder = new System.Text.StringBuilder();

            foreach (var container in containers)
            {
                var name = container.Names.FirstOrDefault()?.TrimStart('/') ?? container.ID.Substring(0, 12);
                var project = container.Labels.ContainsKey("com.docker.compose.project") ? container.Labels["com.docker.compose.project"] : "unknown";
                
                logBuilder.AppendLine($"--- Logs for {project} / {name} ---");
                
                try 
                {
                    // 复用 GetContainerLogsAsync
                    var logs = await GetContainerLogsAsync(container.ID, lines);
                    logBuilder.AppendLine(logs);
                }
                catch (Exception ex)
                {
                    logBuilder.AppendLine($"Failed to get logs: {ex.Message}");
                }
                logBuilder.AppendLine();
            }

            return logBuilder.ToString();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetComposeLogs error: {ex.Message}");
            return $"Error getting compose logs: {ex.Message}";
        }
    }

    // --- Helper Methods for Compose CLI (Still needed for Up/Down) ---

    private async Task<string> ExecuteDockerComposeCommandAsync(string arguments, string workingDirectory = "")
    {
        // 尝试执行 docker-compose，如果不存在则捕获异常提示用户
        try
        {
            var (fileName, args) = GetDockerComposeCommand(arguments);
            var processInfo = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = args,
                WorkingDirectory = workingDirectory,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            using var process = Process.Start(processInfo);
            if (process == null) return "Failed to start docker-compose";
            await process.WaitForExitAsync();
            return await process.StandardOutput.ReadToEndAsync();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Docker Compose CLI not found or failed: {ex.Message}");
            return $"Docker Compose CLI execution failed: {ex.Message}. Make sure docker-compose is installed if you need this feature.";
        }
    }

    private static (string fileName, string arguments) GetDockerComposeCommand(string arguments)
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            return ("docker", $"compose {arguments}");
        }
        return ("docker-compose", arguments);
    }
    
    // 保留流式执行方法用于 Compose Pull 等
    public async Task<int> ExecuteDockerComposeCommandStreamAsync(
        string arguments,
        string workingDirectory,
        Func<string, CancellationToken, Task> onLine,
        CancellationToken cancellationToken = default)
    {
        try 
        {
            var (fileName, args) = GetDockerComposeCommand(arguments);
            var processInfo = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = args,
                WorkingDirectory = workingDirectory,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            using var process = Process.Start(processInfo);
            if (process == null) return -1;

            var outputTask = ReadStreamAsync(process.StandardOutput, "", onLine, cancellationToken);
            var errorTask = ReadStreamAsync(process.StandardError, "stderr", onLine, cancellationToken);
            
            await Task.WhenAll(outputTask, errorTask);
            await process.WaitForExitAsync(cancellationToken);
            return process.ExitCode;
        }
        catch (Exception ex)
        {
            await onLine($"[Error] Failed to execute docker-compose: {ex.Message}", cancellationToken);
            return -1;
        }
    }

    private async Task ReadStreamAsync(StreamReader reader, string prefix, Func<string, CancellationToken, Task> onLine, CancellationToken ct)
    {
        while (!reader.EndOfStream)
        {
            var line = await reader.ReadLineAsync();
            if (line != null)
            {
                await onLine(string.IsNullOrEmpty(prefix) ? line : $"[{prefix}] {line}", ct);
            }
        }
    }

    /// <summary>
    /// 获取 Docker 系统信息
    /// </summary>
    public async Task<DockerSystemInfo> GetSystemInfoAsync()
    {
        try
        {
            var info = await _client.System.GetSystemInfoAsync();
            return new DockerSystemInfo
            {
                Containers = info.Containers,
                ContainersRunning = info.ContainersRunning,
                ContainersPaused = info.ContainersPaused,
                ContainersStopped = info.ContainersStopped,
                Images = info.Images,
                ServerVersion = info.ServerVersion,
                OsType = info.OSType
            };
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetSystemInfo error: {ex.Message}");
            return new DockerSystemInfo();
        }
    }
}


