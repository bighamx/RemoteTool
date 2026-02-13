using System.IO;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services.RemoteControl;
using ChuckieHelper.WebApi.Models.RemoteControl;

namespace ChuckieHelper.WebApi.Controllers.RemoteControl;

[ApiController]
[Route("api/[controller]")]
[Authorize]
public class DockerController : ControllerBase
    
{
    private readonly IDockerService _dockerService;

    public DockerController(IDockerService dockerService)
    {
        _dockerService = dockerService;
    }

    private IActionResult ApiError(string message)
        => BadRequest(new { message });

    /// <summary>
    /// 获取容器列表
    /// </summary>
    [HttpGet("containers")]
    public async Task<IActionResult> GetContainers()
    {
        try
        {
            var containers = await _dockerService.GetContainersAsync();
            if (containers == null || containers.Count == 0)
            {
                return Ok(new { data = containers, message = "No containers found or Docker daemon may not be running" });
            }
            return Ok(new { data = containers });
        }
        catch (Exception ex)
        {
            return ApiError($"Failed to get containers: {ex.Message}");
        }
    }

    /// <summary>
    /// 启动容器
    /// </summary>
    [HttpPost("containers/{containerId}/start")]
    public async Task<IActionResult> StartContainer(string containerId)
    {
        try
        {
            var result = await _dockerService.StartContainerAsync(containerId);
            if (result)
                return Ok(new { message = $"Container {containerId} started" });
            return ApiError($"Failed to start container {containerId}");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 停止容器
    /// </summary>
    [HttpPost("containers/{containerId}/stop")]
    public async Task<IActionResult> StopContainer(string containerId)
    {
        try
        {
            var result = await _dockerService.StopContainerAsync(containerId);
            if (result)
                return Ok(new { message = $"Container {containerId} stopped" });
            return ApiError($"Failed to stop container {containerId}");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 删除容器
    /// </summary>
    [HttpPost("containers/{containerId}")]
    public async Task<IActionResult> RemoveContainer(string containerId, [FromQuery] bool force = false)
    {
        try
        {
            var result = await _dockerService.RemoveContainerAsync(containerId, force);
            if (result)
                return Ok(new { message = $"Container {containerId} removed" });
            return ApiError($"Failed to remove container {containerId}");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 获取容器日志
    /// </summary>
    [HttpGet("containers/{containerId}/logs")]
    public async Task<IActionResult> GetContainerLogs(string containerId, [FromQuery] int lines = 100)
    {
        try
        {
            var logs = await _dockerService.GetContainerLogsAsync(containerId, lines);
            return Ok(new { logs });
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 获取容器资源使用统计（CPU/内存）
    /// </summary>
    [HttpGet("containers/stats")]
    public async Task<IActionResult> GetContainerStats()
    {
        try
        {
            var stats = await _dockerService.GetContainerStatsAsync();
            return Ok(new { data = stats });
        }
        catch (Exception ex)
        {
            return ApiError($"Failed to get container stats: {ex.Message}");
        }
    }

    /// <summary>
    /// 获取镜像列表
    /// </summary>
    [HttpGet("images")]
    public async Task<IActionResult> GetImages()
    {
        try
        {
            var images = await _dockerService.GetImagesAsync();
            return Ok(new { data = images });
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 拉取镜像
    /// </summary>
    [HttpPost("images/pull")]
    public async Task<IActionResult> PullImage([FromBody] PullImageRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.ImageTag))
                return ApiError("ImageTag is required");

            var result = await _dockerService.PullImageAsync(request.ImageTag);
            if (result)
                return Ok(new { message = $"Image {request.ImageTag} pulled successfully" });
            return ApiError($"Failed to pull image {request.ImageTag}");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 检查镜像更新
    /// </summary>
    [HttpPost("images/check-update")]
    public async Task<IActionResult> CheckImageUpdate([FromBody] CheckImageUpdateRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.ImageTag))
                return ApiError("ImageTag is required");

            var hasUpdate = await _dockerService.CheckImageUpdateAsync(request.ImageTag);
            return Ok(new { hasUpdate, imageTag = request.ImageTag });
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 使用 docker-compose 启动
    /// </summary>
    [HttpPost("compose/up")]
    public async Task<IActionResult> ComposeUp([FromBody] ComposeRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.ComposePath))
                return ApiError("ComposePath is required");

            var result = await _dockerService.ComposeUpAsync(request.ComposePath);
            if (result)
                return Ok(new { message = "docker-compose up executed successfully" });
            return ApiError("Failed to execute docker-compose up");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 使用 docker-compose 拉取镜像
    /// </summary>
    [HttpPost("compose/pull")]
    public async Task<IActionResult> ComposePull([FromBody] ComposeRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.ComposePath))
                return ApiError("ComposePath is required");

            var (success, output) = await _dockerService.ComposePullAsync(request.ComposePath);
            if (success)
                return Ok(new { message = "docker-compose pull 执行成功", logs = output });
            return ApiError("docker-compose pull 执行失败");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 流式执行 docker-compose stop
    /// </summary>
    [HttpPost("compose/stop/stream")]
    public Task ComposeStopStream([FromBody] ComposeRequest request, CancellationToken cancellationToken)
        => ExecuteComposeStreamAsync("stop", request, cancellationToken);

    /// <summary>
    /// 流式执行 docker-compose pull
    /// </summary>
    [HttpPost("compose/pull/stream")]
    public Task ComposePullStream([FromBody] ComposeRequest request, CancellationToken cancellationToken)
        => ExecuteComposeStreamAsync("pull", request, cancellationToken);

    /// <summary>
    /// 流式执行 docker-compose up -d
    /// </summary>
    [HttpPost("compose/up/stream")]
    public Task ComposeUpStream([FromBody] ComposeRequest request, CancellationToken cancellationToken)
        => ExecuteComposeStreamAsync("up -d", request, cancellationToken);

    /// <summary>
    /// 流式执行 docker-compose down
    /// </summary>
    [HttpPost("compose/down/stream")]
    public Task ComposeDownStream([FromBody] ComposeRequest request, CancellationToken cancellationToken)
        => ExecuteComposeStreamAsync("down", request, cancellationToken);

    /// <summary>
    /// 使用 docker-compose 停止
    /// </summary>
    [HttpPost("compose/down")]
    public async Task<IActionResult> ComposeDown([FromBody] ComposeRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.ComposePath))
                return ApiError("ComposePath is required");

            var result = await _dockerService.ComposeDownAsync(request.ComposePath);
            if (result)
                return Ok(new { message = "docker-compose down executed successfully" });
            return ApiError("Failed to execute docker-compose down");
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 通用的流式 docker-compose 命令执行器
    /// </summary>
    private async Task ExecuteComposeStreamAsync(string command, ComposeRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrEmpty(request.ComposePath))
        {
            Response.StatusCode = 400;
            await Response.WriteAsync("ComposePath is required", cancellationToken);
            return;
        }
        if (!System.IO.File.Exists(request.ComposePath))
        {
            Response.StatusCode = 400;
            await Response.WriteAsync("Compose 文件不存在", cancellationToken);
            return;
        }
        Response.ContentType = "text/plain; charset=utf-8";
        Response.Headers.CacheControl = "no-cache";
        var dir = Path.GetDirectoryName(request.ComposePath) ?? "";
        try
        {
            var exitCode = await _dockerService.ExecuteDockerComposeCommandStreamAsync(
                command, dir,
                async (line, ct) =>
                {
                    await Response.WriteAsync(line + "\n", ct);
                    await Response.Body.FlushAsync(ct);
                },
                cancellationToken);
            await Response.WriteAsync($"[EXIT:{exitCode}]\n", cancellationToken);
            await Response.Body.FlushAsync(cancellationToken);
        }
        catch (Exception ex)
        {
            await Response.WriteAsync($"[stderr] {ex.Message}\n[EXIT:-1]\n", cancellationToken);
        }
    }

    /// <summary>
    /// 获取 Docker 系统信息
    /// </summary>
    [HttpGet("info")]
    public async Task<IActionResult> GetSystemInfo()
    {
        try
        {
            var info = await _dockerService.GetSystemInfoAsync();
            return Ok(info);
        }
        catch (Exception ex)
        {
            return ApiError(ex.Message);
        }
    }

    /// <summary>
    /// 读取 docker-compose 文件内容
    /// </summary>
    [HttpGet("compose/read")]
    public async Task<IActionResult> ReadComposeFile([FromQuery] string path)
    {
        try
        {
            if (string.IsNullOrEmpty(path))
            {
                return BadRequest(new { message = "Path is required" });
            }

            var content = await _dockerService.ReadComposeFileAsync(path);
            return Ok(new { content });
        }
        catch (Exception ex)
        {
            return BadRequest(new { message = ex.Message });
        }
    }

    /// <summary>
    /// 写入 docker-compose 文件内容
    /// </summary>
    [HttpPost("compose/write")]
    public async Task<IActionResult> WriteComposeFile([FromBody] WriteComposeRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.Path))
            {
                return BadRequest(new { message = "Path is required" });
            }

            if (string.IsNullOrEmpty(request.Content))
            {
                return BadRequest(new { message = "Content is required" });
            }

            var result = await _dockerService.WriteComposeFileAsync(request.Path, request.Content);
            if (result)
            {
                return Ok(new { message = "File saved successfully" });
            }
            return BadRequest(new { message = "Failed to save file" });
        }
        catch (Exception ex)
        {
            return BadRequest(new { message = ex.Message });
        }
    }

    /// <summary>
    /// 验证 docker-compose 文件
    /// </summary>
    [HttpPost("compose/validate")]
    public async Task<IActionResult> ValidateComposeFile([FromBody] ValidateComposeRequest request)
    {
        try
        {
            if (string.IsNullOrEmpty(request.Content))
            {
                return BadRequest(new { message = "Content is required" });
            }

            var (isValid, message) = await _dockerService.ValidateComposeFileAsync(request.Path ?? "docker-compose.yml", request.Content);
            if (isValid)
            {
                return Ok(new { message = "Compose file is valid", details = message });
            }
            return BadRequest(new { message = "Compose file is invalid", details = message });
        }
        catch (Exception ex)
        {
            return BadRequest(new { message = ex.Message });
        }
    }

    /// <summary>
    /// 获取 docker-compose 运行状态
    /// </summary>
    [HttpGet("compose/status")]
    public async Task<IActionResult> GetComposeStatus()
    {
        try
        {
            var projects = await _dockerService.GetComposeStatusAsync();
            return Ok(new { data = projects });
        }
        catch (Exception ex)
        {
            return BadRequest(new { message = ex.Message });
        }
    }

    /// <summary>
    /// 获取 docker-compose 日志
    /// </summary>
    [HttpGet("compose/logs")]
    public async Task<IActionResult> GetComposeLogs([FromQuery] int lines = 100)
    {
        try
        {
            var logs = await _dockerService.GetComposeLogsAsync(lines);
            return Ok(new { logs });
        }
        catch (Exception ex)
        {
            return BadRequest(new { message = ex.Message });
        }
    }

    /// <summary>
    /// 诊断 Docker 连接
    /// </summary>
    [HttpGet("diagnostic")]
    public async Task<IActionResult> Diagnostic()
    {
        var infoTask = _dockerService.GetSystemInfoAsync();
        var containersTask = _dockerService.GetContainersAsync();
        var imagesTask = _dockerService.GetImagesAsync();

        await Task.WhenAll(infoTask, containersTask, imagesTask);

        var diagnostics = new
        {
            timestamp = DateTime.UtcNow,
            dockerVersion = await infoTask,
            containerCount = (await containersTask).Count,
            imageCount = (await imagesTask).Count
        };

        return Ok(diagnostics);
    }
}


