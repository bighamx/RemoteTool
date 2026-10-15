using RemoteTool.WebApi.Services.RemoteControl;

var previous = Environment.GetEnvironmentVariable("REMOTETOOL_DOCKER_PATH");
try
{
    Environment.SetEnvironmentVariable("REMOTETOOL_DOCKER_PATH", Path.Combine(Path.GetTempPath(), "missing-docker-" + Guid.NewGuid().ToString("N")));
    var service = new DockerService();
    var checks = 0;
    async Task MustFail(Func<Task> read, string name)
    {
        try { await read(); throw new Exception(name + " was reported as an empty success"); }
        catch (IOException) { checks++; }
    }
    await MustFail(async () => { await service.GetContainersAsync(); }, "containers");
    await MustFail(async () => { await service.GetImagesAsync(); }, "images");
    await MustFail(async () => { await service.GetContainerStatsAsync(); }, "stats");
    await MustFail(async () => { await service.GetComposeStatusAsync(); }, "compose");
    var sample = """{"ID":"abc","Names":"front","Image":"image:latest","Status":"Up","State":"running","Labels":"com.docker.compose.project=sample,com.docker.compose.project.config_files=C:/one.yml,C:/two.yml,com.docker.compose.project.working_dir=C:/app"}""";
    var projects = DockerComposeFallback.Parse(sample + "\n");
    if (projects.Count != 1 || projects[0].Name != "sample" || projects[0].ContainerCount != 1 ||
        projects[0].ConfigFiles != "C:/one.yml,C:/two.yml" || projects[0].Status != "running")
        throw new Exception("Compose labels did not recover the project");
    checks++;
    Console.WriteLine($"Docker unavailable-state checks: {checks} passed");
}
finally { Environment.SetEnvironmentVariable("REMOTETOOL_DOCKER_PATH", previous); }
