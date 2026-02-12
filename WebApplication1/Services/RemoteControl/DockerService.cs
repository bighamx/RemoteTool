using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

public class DockerService : IDockerService
    
{



    /// <summary>
    /// 获取 Docker 容器列表
    /// </summary>
    public async Task<List<DockerContainer>> GetContainersAsync()
    {
        try
        {
            // 使用简单的管道符分隔格式，避免 JSON 截断问题
            // Format: ID|Names|Image|Status|Ports|State
            var output = await ExecuteDockerCommandAsync("ps -a --no-trunc --format \"{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}|{{.State}}\"");
            if (string.IsNullOrEmpty(output))
            {
                Console.WriteLine("Docker ps returned empty output");
                return new List<DockerContainer>();
            }

            var lines = output.Split("\n", StringSplitOptions.RemoveEmptyEntries);
            var containers = new List<DockerContainer>();

            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                try
                {
                    var parts = line.Split("|");
                    if (parts.Length < 6)
                    {
                        Console.WriteLine($"Warning: Container line has unexpected format (parts count: {parts.Length}): {line}");
                        continue;
                    }

                    containers.Add(new DockerContainer
                    {
                        Id = parts[0].Trim(),
                        Names = parts[1].Trim(),
                        Image = parts[2].Trim(),
                        Status = parts[3].Trim(),
                        Ports = parts[4].Trim(),
                        State = parts[5].Trim()
                    });
                }
                catch (Exception lineEx)
                {
                    Console.WriteLine($"Failed to parse container line: {line}. Error: {lineEx.Message}");
                    continue;
                }
            }

            Console.WriteLine($"Successfully retrieved {containers.Count} containers");
            return containers;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetContainers error: {ex.Message}");
            Console.WriteLine($"Stack trace: {ex.StackTrace}");

            // Fallback: Try with json format but with better error handling
            return await GetContainersAsyncJsonFallback();
        }
    }

    /// <summary>
    /// JSON 格式的备用方法（当 table 格式失败时使用）
    /// </summary>
    private async Task<List<DockerContainer>> GetContainersAsyncJsonFallback()
    {
        try
        {
            var output = await ExecuteDockerCommandAsync("ps -a --format json");
            if (string.IsNullOrEmpty(output))
            {
                return new List<DockerContainer>();
            }

            // Docker ps --format json 返回 NDJSON 格式（每行一个 JSON 对象）
            var lines = output.Split("\n", StringSplitOptions.RemoveEmptyEntries);
            var containers = new List<DockerContainer>();

            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                try
                {
                    using var document = JsonDocument.Parse(line);
                    var element = document.RootElement;

                    containers.Add(new DockerContainer
                    {
                        Id = element.TryGetProperty("ID", out var idProp) ? idProp.GetString() ?? "" : "",
                        Names = element.TryGetProperty("Names", out var namesProp) ? namesProp.GetString() ?? "" : "",
                        Image = element.TryGetProperty("Image", out var imageProp) ? imageProp.GetString() ?? "" : "",
                        Status = element.TryGetProperty("Status", out var statusProp) ? statusProp.GetString() ?? "" : "",
                        Ports = element.TryGetProperty("Ports", out var portsProp) ? portsProp.GetString() ?? "" : "",
                        State = element.TryGetProperty("State", out var stateProp) ? stateProp.GetString() ?? "" : ""
                    });
                }
                catch (JsonException jsonEx)
                {
                    Console.WriteLine($"JSON parsing error for container. Line length: {line.Length}. Error: {jsonEx.Message}");
                    // Try to extract basic info manually if JSON parsing fails
                    var basicInfo = TryExtractBasicContainerInfo(line);
                    if (basicInfo != null)
                    {
                        containers.Add(basicInfo);
                    }
                    continue;
                }
                catch (Exception lineEx)
                {
                    Console.WriteLine($"Failed to parse container line: Error: {lineEx.Message}");
                    continue;
                }
            }

            Console.WriteLine($"Successfully retrieved {containers.Count} containers (JSON fallback)");
            return containers;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetContainers JSON fallback error: {ex.Message}");
            return new List<DockerContainer>();
        }
    }

    /// <summary>
    /// 尝试从损坏的 JSON 中提取基本容器信息
    /// </summary>
    private DockerContainer? TryExtractBasicContainerInfo(string jsonLine)
    {
        try
        {
            // 尝试用正则表达式提取关键字段
            var idMatch = System.Text.RegularExpressions.Regex.Match(jsonLine, @"""ID"":\s*""([^""]+)""");
            var namesMatch = System.Text.RegularExpressions.Regex.Match(jsonLine, @"""Names"":\s*""([^""]+)""");
            var imageMatch = System.Text.RegularExpressions.Regex.Match(jsonLine, @"""Image"":\s*""([^""]+)""");
            var stateMatch = System.Text.RegularExpressions.Regex.Match(jsonLine, @"""State"":\s*""([^""]+)""");
            var statusMatch = System.Text.RegularExpressions.Regex.Match(jsonLine, @"""Status"":\s*""([^""]+)""");
            var portsMatch = System.Text.RegularExpressions.Regex.Match(jsonLine, @"""Ports"":\s*""([^""]+)""");

            if (idMatch.Success && namesMatch.Success)
            {
                return new DockerContainer
                {
                    Id = idMatch.Groups[1].Value,
                    Names = namesMatch.Groups[1].Value,
                    Image = imageMatch.Success ? imageMatch.Groups[1].Value : "",
                    State = stateMatch.Success ? stateMatch.Groups[1].Value : "",
                    Status = statusMatch.Success ? statusMatch.Groups[1].Value : "",
                    Ports = portsMatch.Success ? portsMatch.Groups[1].Value : ""
                };
            }

            return null;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Error extracting basic container info: {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// 获取所有运行中容器的资源使用统计（CPU/内存）
    /// </summary>
    public async Task<List<ContainerUsageStats>> GetContainerStatsAsync()
    {
        try
        {
            // docker stats --no-stream 返回当前快照，不会持续输出
            // 格式: ID|Name|CPUPerc|MemUsage|MemPerc
            var output = await ExecuteDockerCommandAsync(
                "stats --no-stream --format \"{{.ID}}|{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}\"",
                timeoutSeconds: 15);

            if (string.IsNullOrEmpty(output))
            {
                return new List<ContainerUsageStats>();
            }

            var lines = output.Split("\n", StringSplitOptions.RemoveEmptyEntries);
            var statsList = new List<ContainerUsageStats>();

            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                try
                {
                    var parts = line.Split("|");
                    if (parts.Length < 5)
                    {
                        Console.WriteLine($"Warning: Stats line has unexpected format: {line}");
                        continue;
                    }

                    statsList.Add(new ContainerUsageStats
                    {
                        Id = parts[0].Trim(),
                        Name = parts[1].Trim(),
                        CpuPercent = parts[2].Trim(),
                        MemUsage = parts[3].Trim(),
                        MemPercent = parts[4].Trim()
                    });
                }
                catch (Exception lineEx)
                {
                    Console.WriteLine($"Failed to parse stats line: {line}. Error: {lineEx.Message}");
                }
            }

            return statsList;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetContainerStats error: {ex.Message}");
            return new List<ContainerUsageStats>();
        }
    }

    /// <summary>
    /// 启动容器
    /// </summary>
    public async Task<bool> StartContainerAsync(string containerId)
    {
        try
        {
            var result = await ExecuteDockerCommandAsync($"start {containerId}");
            return !string.IsNullOrEmpty(result);
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
            var result = await ExecuteDockerCommandAsync($"stop {containerId}");
            return !string.IsNullOrEmpty(result);
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
            var forceFlag = force ? " -f" : "";
            var result = await ExecuteDockerCommandAsync($"rm{forceFlag} {containerId}");
            return !string.IsNullOrEmpty(result);
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
    /// <summary>
    /// 获取容器日志（支持超时）
    /// </summary>
    public async Task<string> GetContainerLogsAsync(string containerId, int lines = 100, int timeoutSeconds = 10)
    {
        try
        {
            return await ExecuteDockerCommandAsync($"logs --tail {lines} {containerId}", timeoutSeconds);
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
            // 使用简单的管道符分隔格式，避免 JSON 截断问题
            // Format: ID|Repository|Tag|Size|CreatedAt
            var output = await ExecuteDockerCommandAsync("images --no-trunc --format \"{{.ID}}|{{.Repository}}|{{.Tag}}|{{.Size}}|{{.CreatedAt}}\"");
            if (string.IsNullOrEmpty(output))
            {
                Console.WriteLine("Docker images returned empty output");
                return new List<DockerImage>();
            }

            var lines = output.Split("\n", StringSplitOptions.RemoveEmptyEntries);
            var images = new List<DockerImage>();

            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                try
                {
                    var parts = line.Split("|");
                    if (parts.Length < 5)
                    {
                        Console.WriteLine($"Warning: Image line has unexpected format (parts count: {parts.Length}): {line}");
                        continue;
                    }

                    images.Add(new DockerImage
                    {
                        Id = parts[0].Trim(),
                        Repository = parts[1].Trim(),
                        Tag = parts[2].Trim(),
                        Size = parts[3].Trim(),
                        Created = parts[4].Trim()
                    });
                }
                catch (Exception lineEx)
                {
                    Console.WriteLine($"Failed to parse image line: {line}. Error: {lineEx.Message}");
                    continue;
                }
            }

            Console.WriteLine($"Successfully retrieved {images.Count} images");
            return images;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetImages error: {ex.Message}");
            Console.WriteLine($"Stack trace: {ex.StackTrace}");

            // Fallback: Try with json format
            return await GetImagesAsyncJsonFallback();
        }
    }

    /// <summary>
    /// JSON 格式的备用方法（当 table 格式失败时使用）
    /// </summary>
    private async Task<List<DockerImage>> GetImagesAsyncJsonFallback()
    {
        try
        {
            var output = await ExecuteDockerCommandAsync("images --format json");
            if (string.IsNullOrEmpty(output))
            {
                Console.WriteLine("Docker images JSON fallback returned empty output");
                return new List<DockerImage>();
            }

            // Docker images --format json 返回 NDJSON 格式
            var lines = output.Split("\n", StringSplitOptions.RemoveEmptyEntries);
            var images = new List<DockerImage>();

            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                try
                {
                    using var document = JsonDocument.Parse(line);
                    var element = document.RootElement;

                    images.Add(new DockerImage
                    {
                        Id = element.TryGetProperty("ID", out var idProp) ? idProp.GetString() ?? "" : "",
                        Repository = element.TryGetProperty("Repository", out var repoProp) ? repoProp.GetString() ?? "" : "",
                        Tag = element.TryGetProperty("Tag", out var tagProp) ? tagProp.GetString() ?? "" : "",
                        Size = element.TryGetProperty("Size", out var sizeProp) ? sizeProp.GetString() ?? "" : "",
                        Created = element.TryGetProperty("CreatedAt", out var createdProp) ? createdProp.GetString() ?? "" : ""
                    });
                }
                catch (JsonException jsonEx)
                {
                    Console.WriteLine($"JSON parsing error for image. Line length: {line.Length}. Error: {jsonEx.Message}");
                    continue;
                }
                catch (Exception lineEx)
                {
                    Console.WriteLine($"Failed to parse image line: Error: {lineEx.Message}");
                    continue;
                }
            }

            Console.WriteLine($"Successfully retrieved {images.Count} images (JSON fallback)");
            return images;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetImages JSON fallback error: {ex.Message}");
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
            var result = await ExecuteDockerCommandAsync($"pull {imageTag}");
            return !string.IsNullOrEmpty(result);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"PullImage error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 检查镜像是否有新版本（简单实现：尝试拉取以检查）
    /// </summary>
    public async Task<bool> CheckImageUpdateAsync(string imageTag)
    {
        try
        {
            // 这是一个简化版本，实际可能需要查询 Docker Hub API
            var output = await ExecuteDockerCommandAsync($"pull {imageTag}");
            return output.Contains("Downloaded newer image") || output.Contains("Image is up to date");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"CheckImageUpdate error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// 使用 docker-compose 创建和启动容器
    /// </summary>
    public async Task<bool> ComposeUpAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath))
            {
                return false;
            }

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
    /// 使用 docker-compose 停止容器
    /// </summary>
    public async Task<bool> ComposeDownAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath))
            {
                return false;
            }

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
    /// 使用 docker-compose 停止容器（仅停止，不移除）
    /// </summary>
    public async Task<bool> ComposeStopAsync(string composePath)
    {
        try
        {
            if (!File.Exists(composePath))
            {
                return false;
            }
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
            if (!File.Exists(composePath))
            {
                return (false, "Compose 文件不存在");
            }

            var directory = Path.GetDirectoryName(composePath) ?? "";
            var result = await ExecuteDockerComposeCommandAsync("pull", directory);
            var success = !string.IsNullOrEmpty(result);
            return (success, result);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ComposePull error: {ex.Message}");
            return (false, ex.Message);
        }
    }

    /// <summary>
    /// 读取 docker-compose 文件内容
    /// </summary>
    public async Task<string> ReadComposeFileAsync(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                throw new FileNotFoundException($"File not found: {path}");
            }

            return await File.ReadAllTextAsync(path);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ReadComposeFile error: {ex.Message}");
            throw;
        }
    }

    /// <summary>
    /// 写入 docker-compose 文件内容
    /// </summary>
    public async Task<bool> WriteComposeFileAsync(string path, string content)
    {
        try
        {
            // 验证路径不为空
            if (string.IsNullOrWhiteSpace(path))
            {
                throw new ArgumentException("Path cannot be empty or whitespace");
            }

            // 获取目录路径
            var directory = Path.GetDirectoryName(path);

            if (!string.IsNullOrEmpty(directory))
            {
                // Windows：验证驱动器存在且可访问
                if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                {
                    var drive = Path.GetPathRoot(directory);
                    if (drive != null && drive.Length >= 2 && drive[0] >= 'A' && drive[0] <= 'Z')
                    {
                        var driveInfo = new System.IO.DriveInfo(drive);
                        if (!driveInfo.IsReady)
                        {
                            throw new DirectoryNotFoundException($"Drive {drive} is not accessible or does not exist");
                        }
                    }
                }

                // 创建目录
                if (!Directory.Exists(directory))
                {
                    try
                    {
                        Directory.CreateDirectory(directory);
                        Console.WriteLine($"Created directory: {directory}");
                    }
                    catch (UnauthorizedAccessException uaEx)
                    {
                        throw new UnauthorizedAccessException($"Access denied when creating directory '{directory}': {uaEx.Message}", uaEx);
                    }
                    catch (IOException ioEx)
                    {
                        throw new IOException($"IO error when creating directory '{directory}': {ioEx.Message}", ioEx);
                    }
                }
            }

            // 验证可以写入目录
            if (!string.IsNullOrEmpty(directory) && !IsDirectoryWritable(directory))
            {
                throw new UnauthorizedAccessException($"Cannot write to directory: {directory}");
            }

            await File.WriteAllTextAsync(path, content);
            Console.WriteLine($"WriteComposeFile succeeded: {path}");
            return true;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"WriteComposeFile error: {ex.Message}");
            Console.WriteLine($"WriteComposeFile stack trace: {ex.StackTrace}");
            throw;
        }
    }

    /// <summary>
    /// 检查目录是否可写
    /// </summary>
    private bool IsDirectoryWritable(string directoryPath)
    {
        try
        {
            var testFileName = Path.Combine(directoryPath, $".test-{Guid.NewGuid()}.tmp");
            using (var fs = File.Create(testFileName, 1, FileOptions.DeleteOnClose))
            {
                // 文件会在流关闭时自动删除
            }
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 验证 docker-compose 文件
    /// </summary>
    public async Task<(bool isValid, string message)> ValidateComposeFileAsync(string path, string content)
    {
        try
        {
            // 首先进行基本的 YAML 结构验证
            var basicValidation = ValidateComposeYamlStructure(content);
            if (!basicValidation.isValid)
            {
                Console.WriteLine($"ValidateCompose YAML structure error: {basicValidation.message}");
                return (false, basicValidation.message);
            }

            // 写入临时文件
            var tempPath = Path.Combine(Path.GetTempPath(), $"docker-compose-{Guid.NewGuid()}.yml");

            try
            {
                await File.WriteAllTextAsync(tempPath, content);
                Console.WriteLine($"Created temp compose file: {tempPath}");

                var directory = Path.GetDirectoryName(tempPath) ?? "";
                var result = await ExecuteDockerComposeCommandAsync($"-f {tempPath} config", directory);

                // 检查结果中是否有错误
                if (string.IsNullOrEmpty(result))
                {
                    Console.WriteLine("ValidateCompose returned empty result");
                    return (false, "Docker compose validation returned empty result");
                }

                if (result.Contains("ERROR") || result.Contains("error"))
                {
                    Console.WriteLine($"ValidateCompose error result: {result}");
                    return (false, $"Compose validation failed: {result}");
                }

                Console.WriteLine("ValidateCompose passed successfully");
                return (true, "Compose file is valid");
            }
            finally
            {
                if (File.Exists(tempPath))
                {
                    try
                    {
                        File.Delete(tempPath);
                        Console.WriteLine($"Deleted temp file: {tempPath}");
                    }
                    catch (Exception deleteEx)
                    {
                        Console.WriteLine($"Warning: Failed to delete temp file {tempPath}: {deleteEx.Message}");
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ValidateCompose error: {ex.Message}");
            Console.WriteLine($"ValidateCompose stack trace: {ex.StackTrace}");
            return (false, $"Validation error: {ex.Message}");
        }
    }

    /// <summary>
    /// 验证 docker-compose YAML 基本结构
    /// </summary>
    private (bool isValid, string message) ValidateComposeYamlStructure(string content)
    {
        if (string.IsNullOrWhiteSpace(content))
        {
            return (false, "Content is empty or whitespace");
        }

        var lines = content.Split("\n", StringSplitOptions.RemoveEmptyEntries);

        // 检查是否包含 'services' 关键字
        var hasServices = false;
        var servicesLineNumber = 0;

        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];

            // 跳过注释和空白行
            if (line.TrimStart().StartsWith("#") || string.IsNullOrWhiteSpace(line))
                continue;

            // 检查服务定义
            if (line.TrimStart().StartsWith("services"))
            {
                if (!line.Contains(":"))
                {
                    return (false, $"Line {i + 1}: 'services' must be followed by ':' (e.g., 'services:')");
                }
                hasServices = true;
                servicesLineNumber = i;
                break;
            }
        }

        if (!hasServices)
        {
            return (false, "Missing 'services:' definition in compose file");
        }

        // 检查 'services:' 后是否有有效的服务定义
        if (servicesLineNumber + 1 >= lines.Length)
        {
            return (false, "No service definitions found after 'services:'");
        }

        // 检查服务定义是否正确缩进（应该比 'services' 多缩进）
        var servicesLine = lines[servicesLineNumber];
        var servicesIndent = servicesLine.Length - servicesLine.TrimStart().Length;
        var nextLine = lines[servicesLineNumber + 1];
        var nextIndent = nextLine.Length - nextLine.TrimStart().Length;

        if (nextIndent <= servicesIndent && !string.IsNullOrWhiteSpace(nextLine.Trim()))
        {
            return (false, $"Service definitions must be indented more than 'services:' (line {servicesLineNumber + 2})");
        }

        return (true, "YAML structure looks valid");
    }

    /// <summary>
    /// 按 compose 项目名（label）获取该项目下的容器列表
    /// </summary>
    public async Task<List<ComposeContainerInfo>> GetContainersByComposeProjectAsync(string projectName)
    {
        if (string.IsNullOrWhiteSpace(projectName))
            return new List<ComposeContainerInfo>();

        try
        {
            var filter = $"label=com.docker.compose.project={projectName}";
            var format = "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.State}}";
            var output = await ExecuteDockerCommandAsync($"ps -a --no-trunc --filter \"{filter}\" --format \"{format}\"");
            if (string.IsNullOrEmpty(output))
                return new List<ComposeContainerInfo>();

            var list = new List<ComposeContainerInfo>();
            foreach (var line in output.Split("\n", StringSplitOptions.RemoveEmptyEntries))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                var parts = line.Split("|");
                if (parts.Length < 5) continue;
                list.Add(new ComposeContainerInfo
                {
                    Id = parts[0].Trim(),
                    Names = parts[1].Trim(),
                    Image = parts[2].Trim(),
                    Status = parts[3].Trim(),
                    State = parts[4].Trim()
                });
            }
            return list;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetContainersByComposeProject error: {ex.Message}");
            return new List<ComposeContainerInfo>();
        }
    }

    /// <summary>
    /// 获取 docker-compose 运行状态
    /// </summary>
    public async Task<List<ComposeProject>> GetComposeStatusAsync()
    {
        try

        {
            // 使用 docker compose ls --all 同时显示运行中与已停止的项目
            var output = await ExecuteDockerCommandAsync("compose ls --all");

            if (string.IsNullOrEmpty(output))
            {
                Console.WriteLine("No docker-compose projects found (empty output)");
                return new List<ComposeProject>();
            }

            Console.WriteLine($"GetComposeStatus output length: {output.Length}");
            var projects = new List<ComposeProject>();
            var lines = output.Split("\n", StringSplitOptions.RemoveEmptyEntries);

            Console.WriteLine($"GetComposeStatus found {lines.Length} lines");

            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                // 跳过表头行（通常包含 "NAME" 或 "STATUS"）
                if (line.Contains("NAME") || line.Contains("STATUS") || line.StartsWith("-"))
                {
                    Console.WriteLine($"GetComposeStatus skipping header: {line}");
                    continue;
                }

                try
                {
                    // docker compose ls 输出格式通常是空格分隔或制表符分隔
                    // NAME                 STATUS               CONFIG FILES
                    // example-project      running(2)           /path/to/docker-compose.yml

                    var parts = System.Text.RegularExpressions.Regex.Split(line, @"\s{2,}");
                    if (parts.Length < 2)
                    {
                        Console.WriteLine($"Warning: Compose line has unexpected format (parts: {parts.Length}): {line}");
                        continue;
                    }

                    var projectName = parts[0].Trim();
                    var statusStr = parts.Length > 1 ? parts[1].Trim() : "";
                    var configFiles = parts.Length > 2 ? parts[2].Trim() : "";

                    Console.WriteLine($"GetComposeStatus parsing - Name: {projectName}, Status: {statusStr}, Config: {configFiles}");

                    // 从status中提取运行状态和容器数量
                    // 例如: "running(2)" -> status=running, count=2
                    string status = statusStr;
                    int containerCount = 0;
                    var match = System.Text.RegularExpressions.Regex.Match(statusStr, @"(\w+)\((\d+)\)");
                    if (match.Success)
                    {
                        status = match.Groups[1].Value;
                        containerCount = int.Parse(match.Groups[2].Value);
                    }

                    var containers = await GetContainersByComposeProjectAsync(projectName);
                    projects.Add(new ComposeProject
                    {
                        Name = projectName,
                        Status = status,
                        ContainerCount = containerCount,
                        ConfigFiles = configFiles,
                        Containers = containers
                    });
                }
                catch (Exception lineEx)
                {
                    Console.WriteLine($"Failed to parse compose line: {line}. Error: {lineEx.Message}");
                    continue;
                }
            }

            Console.WriteLine($"GetComposeStatus returned {projects.Count} compose projects");
            return projects;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetComposeStatus error: {ex.Message}");
            Console.WriteLine($"Stack trace: {ex.StackTrace}");
            return new List<ComposeProject>();
        }
    }

    /// <summary>
    /// 获取 docker-compose 日志
    /// </summary>
    public async Task<string> GetComposeLogsAsync(int lines = 100)
    {
        try
        {
            // 这里可以根据需要实现获取特定项目的日志
            // 简单起见，我们获取最近运行的容器的日志
            var containers = await GetContainersAsync();
            if (containers.Count == 0)
            {
                return "No containers found";
            }

            var logs = new System.Text.StringBuilder();
            foreach (var container in containers.Take(5)) // 限制最多显示5个容器的日志
            {
                if (!string.IsNullOrEmpty(container.Names))
                {
                    var containerLogs = await GetContainerLogsAsync(container.Names, lines);
                    logs.AppendLine($"=== {container.Names} ===");
                    logs.AppendLine(containerLogs);
                    logs.AppendLine();
                }
            }

            return logs.ToString();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetComposeLogs error: {ex.Message}");
            return $"Error: {ex.Message}";
        }
    }

    /// <summary>
    /// 执行 Docker 命令
    /// </summary>
    private async Task<string> ExecuteDockerCommandAsync(string arguments, int timeoutSeconds = 10)
    {
        return await ExecuteCommandAsync("docker", arguments, timeoutSeconds);
    }

    /// <summary>
    /// 执行 docker-compose 命令。Linux 优先使用 "docker compose"（插件），否则 "docker-compose"。
    /// </summary>
    private async Task<string> ExecuteDockerComposeCommandAsync(string arguments, string workingDirectory = "")
    {
        var (fileName, args) = GetDockerComposeCommand(arguments);
        var processInfo = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = args,
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };

        if (!string.IsNullOrEmpty(workingDirectory))
        {
            processInfo.WorkingDirectory = workingDirectory;
        }

        return await ExecuteProcessAsync(processInfo);
    }

    private static (string fileName, string arguments) GetDockerComposeCommand(string arguments)
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
        {
            return ("docker", $"compose {arguments}");
        }
        return ("docker-compose", arguments);
    }

    /// <summary>
    /// 流式执行 docker-compose 命令，每行输出通过 onLine 回调；返回进程退出码。
    /// </summary>
    public async Task<int> ExecuteDockerComposeCommandStreamAsync(
        string arguments,
        string workingDirectory,
        Func<string, CancellationToken, Task> onLine,
        CancellationToken cancellationToken = default)
    {
        var (fileName, args) = GetDockerComposeCommand(arguments);
        var processInfo = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = args,
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };

        if (!string.IsNullOrEmpty(workingDirectory))
        {
            processInfo.WorkingDirectory = workingDirectory;
        }

        using var process = Process.Start(processInfo);
        if (process == null)
        {
            await onLine("[错误] 无法启动 docker-compose 进程", cancellationToken);
            return -1;
        }

        var stdout = process.StandardOutput;
        var stderr = process.StandardError;

        async Task ReadStreamAsync(StreamReader reader, string prefix, CancellationToken ct)
        {
            try
            {
                while (await reader.ReadLineAsync(ct) is { } line)
                {
                    var withPrefix = string.IsNullOrEmpty(prefix) ? line : $"[{prefix}] {line}";
                    await onLine(withPrefix, ct);
                }
            }
            catch (OperationCanceledException) { }
        }

        var stdoutTask = ReadStreamAsync(stdout, "", cancellationToken);
        var stderrTask = ReadStreamAsync(stderr, "stderr", cancellationToken);

        await Task.WhenAll(stdoutTask, stderrTask);
        await process.WaitForExitAsync(cancellationToken);
        return process.ExitCode;
    }

    /// <summary>
    /// 执行系统命令
    /// </summary>
    private async Task<string> ExecuteCommandAsync(string command, string arguments, int timeoutSeconds = 10)
    {
        var processInfo = new ProcessStartInfo
        {
            FileName = command,
            Arguments = arguments,
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };

        return await ExecuteProcessAsync(processInfo, timeoutSeconds);
    }

    /// <summary>
    /// 执行进程
    /// </summary>
    private async Task<string> ExecuteProcessAsync(ProcessStartInfo processInfo, int timeoutSeconds = 10)
    {
        try
        {
            Console.WriteLine($"ExecuteProcess starting: {processInfo.FileName} {processInfo.Arguments}");

            using var process = Process.Start(processInfo);
            if (process == null)
            {
                Console.WriteLine($"Failed to start process: {processInfo.FileName}");
                return "";
            }

            var outputTask = process.StandardOutput.ReadToEndAsync();
            var errorTask = process.StandardError.ReadToEndAsync();
            var waitTask = process.WaitForExitAsync();

            var completedTask = await Task.WhenAny(waitTask, Task.Delay(timeoutSeconds * 30 * 1000));
            if (completedTask != waitTask)
            {
                try { process.Kill(); } catch { }
                Console.WriteLine($"Process timeout: {processInfo.FileName} {processInfo.Arguments}");
                return $"Process timeout after {timeoutSeconds} seconds.";
            }

            var output = await outputTask;
            var error = await errorTask;

            Console.WriteLine($"Process exit code: {process.ExitCode}");
            if (!string.IsNullOrEmpty(output))
            {
                Console.WriteLine($"Process output length: {output.Length} bytes");
            }

            if (!string.IsNullOrEmpty(error))
            {
                Console.WriteLine($"Process error (exit code: {process.ExitCode}): {error}");
                return error;
            }

            if (process.ExitCode != 0)
            {
                Console.WriteLine($"Process exited with code {process.ExitCode}: {processInfo.FileName} {processInfo.Arguments}");
                // 返回错误信息或空字符串，而不是中断
                if (!string.IsNullOrEmpty(output))
                {
                    return output;
                }
            }

            return output;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"ExecuteProcess error: {ex.Message}");
            Console.WriteLine($"Stack trace: {ex.StackTrace}");
            return "";
        }
    }

    /// <summary>
    /// 获取 Docker 系统信息
    /// </summary>
    public async Task<DockerSystemInfo> GetSystemInfoAsync()
    {
        try
        {
            var output = await ExecuteDockerCommandAsync("info --format json");
            if (string.IsNullOrEmpty(output))
            {
                return new DockerSystemInfo();
            }

            using var document = JsonDocument.Parse(output);
            var root = document.RootElement;

            return new DockerSystemInfo
            {
                Containers = root.TryGetProperty("Containers", out var prop) ? prop.GetInt32() : 0,
                ContainersRunning = root.TryGetProperty("ContainersRunning", out var prop2) ? prop2.GetInt32() : 0,
                ContainersPaused = root.TryGetProperty("ContainersPaused", out var prop3) ? prop3.GetInt32() : 0,
                ContainersStopped = root.TryGetProperty("ContainersStopped", out var prop4) ? prop4.GetInt32() : 0,
                Images = root.TryGetProperty("Images", out var prop5) ? prop5.GetInt32() : 0,
                ServerVersion = root.TryGetProperty("ServerVersion", out var prop6) ? prop6.GetString() ?? "" : "",
                OsType = root.TryGetProperty("OSType", out var prop7) ? prop7.GetString() ?? "" : ""
            };
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetSystemInfo error: {ex.Message}");
            return new DockerSystemInfo();
        }
    }
}
