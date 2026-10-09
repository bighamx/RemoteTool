
using System.Collections.Generic;

namespace ChuckieHelper.WebApi.Models.RemoteControl;

public class PullImageRequest
{
    public string ImageTag { get; set; } = "";
}

public class CheckImageUpdateRequest
{
    public string ImageTag { get; set; } = "";
}

public class ComposeRequest
{
    public string ComposePath { get; set; } = "";
}

public class WriteComposeRequest
{
    public string Path { get; set; } = "";
    public string Content { get; set; } = "";
}

public class ValidateComposeRequest
{
    public string? Path { get; set; }
    public string Content { get; set; } = "";
}

/// <summary>
/// Docker 容器基本信息
/// </summary>
public class DockerContainer
{
    public string Id { get; set; } = "";
    public string Names { get; set; } = "";
    public string Image { get; set; } = "";
    public string Status { get; set; } = "";
    public string Ports { get; set; } = "";
    public string State { get; set; } = "";
}

/// <summary>
/// Docker 容器资源使用统计
/// </summary>
public class ContainerUsageStats
{
    /// <summary>容器 ID</summary>
    public string Id { get; set; } = "";
    /// <summary>容器名称</summary>
    public string Name { get; set; } = "";
    /// <summary>CPU 使用百分比，如 "0.50%"</summary>
    public string CpuPercent { get; set; } = "";
    /// <summary>内存使用量/限制，如 "50MiB / 1GiB"</summary>
    public string MemUsage { get; set; } = "";
    /// <summary>内存使用百分比，如 "5.00%"</summary>
    public string MemPercent { get; set; } = "";
}

/// <summary>
/// Docker Compose 项目信息
/// </summary>
public class ComposeProject
{
    public string Name { get; set; } = "";
    public string Status { get; set; } = "";
    public int ContainerCount { get; set; } = 0;
    public string ConfigFiles { get; set; } = "";
    /// <summary>该项目下的容器列表</summary>
    public List<ComposeContainerInfo> Containers { get; set; } = new();
}

/// <summary>
/// Compose 项目下的单个容器简要信息
/// </summary>
public class ComposeContainerInfo
{
    public string Id { get; set; } = "";
    public string Names { get; set; } = "";
    public string Image { get; set; } = "";
    public string Status { get; set; } = "";
    public string State { get; set; } = "";
}

/// <summary>
/// Docker 镜像信息
/// </summary>
public class DockerImage
{
    public string Id { get; set; } = "";
    public string Repository { get; set; } = "";
    public string Tag { get; set; } = "";
    public string Size { get; set; } = "";
    public string Created { get; set; } = "";
}

/// <summary>
/// Docker 系统信息
/// </summary>
public class DockerSystemInfo
{
    public long Containers { get; set; }
    public long ContainersRunning { get; set; }
    public long ContainersPaused { get; set; }
    public long ContainersStopped { get; set; }
    public long Images { get; set; }
    public string ServerVersion { get; set; } = "";
    public string OsType { get; set; } = "";
}
