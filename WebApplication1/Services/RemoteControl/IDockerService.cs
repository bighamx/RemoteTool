using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

public interface IDockerService
{
    Task<List<DockerContainer>> GetContainersAsync();
    Task<List<ContainerUsageStats>> GetContainerStatsAsync();
    Task<bool> StartContainerAsync(string containerId);
    Task<bool> StopContainerAsync(string containerId);
    Task<bool> RemoveContainerAsync(string containerId, bool force = false);
    Task<string> GetContainerLogsAsync(string containerId, int lines = 100, int timeoutSeconds = 10);
    Task<List<DockerImage>> GetImagesAsync();
    Task<bool> PullImageAsync(string imageTag);
    Task<bool> CheckImageUpdateAsync(string imageTag);
    Task<bool> ComposeUpAsync(string composePath);
    Task<bool> ComposeDownAsync(string composePath);
    Task<bool> ComposeStopAsync(string composePath);
    Task<(bool Success, string Output)> ComposePullAsync(string composePath);
    Task<string> ReadComposeFileAsync(string path);
    Task<bool> WriteComposeFileAsync(string path, string content);
    Task<(bool isValid, string message)> ValidateComposeFileAsync(string path, string content);
    Task<List<ComposeContainerInfo>> GetContainersByComposeProjectAsync(string projectName);
    Task<List<ComposeProject>> GetComposeStatusAsync();
    Task<string> GetComposeLogsAsync(int lines = 100);
    Task<DockerSystemInfo> GetSystemInfoAsync();
    
    // Stream methods might be specific, but if used by controller/hub, should be here.
    // ExecuteDockerComposeCommandStreamAsync is public in DockerService (CLI). 
    // LinuxDockerService has it too? Let's check.
    Task<int> ExecuteDockerComposeCommandStreamAsync(
        string arguments,
        string workingDirectory,
        System.Func<string, CancellationToken, Task> onLine,
        CancellationToken cancellationToken = default);
}
