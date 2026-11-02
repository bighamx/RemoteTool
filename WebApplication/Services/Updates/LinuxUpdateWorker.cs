using System.Text.Json.Nodes;
using RemoteTool.Updates;

namespace RemoteTool.WebApi.Services.Updates;

public sealed partial class Worker
{
    Task<string> Systemd(params string[] arguments) {
        SystemdControl.ValidateUnit(config.Pool);
        return SystemdControl.Run(config.UserService, arguments);
    }
    async Task InstallLinuxRelease(string stage) {
        var output = await CurrentDirectory();
        for (var parent = new DirectoryInfo(output); parent != null; parent = parent.Parent)
            if (File.Exists(Path.Combine(parent.FullName,".git")) || Directory.Exists(Path.Combine(parent.FullName,".git"))) throw new IOException("拒绝覆盖开发工作区");
        await Readiness();
        var oldHealth = await Health();
        if (oldHealth["id"]?.ToString() != config.DeviceId) throw new IOException("设备身份已变化");
        var backup = At(Path.Combine("jobs",job.Id,"runtime-backup")); Directory.CreateDirectory(backup);
        foreach (var file in RuntimeFiles(output)) {
            var destination = Path.Combine(backup,Path.GetRelativePath(output,file));
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!); File.Copy(file,destination,true);
        }
        await StopBridge();
        var transaction = new JsonObject { ["kind"]="source", ["previous"]=output, ["target"]=output,
            ["backup"]=backup, ["sequence"]=oldHealth["sequence"]!.DeepClone(), ["buildId"]=oldHealth["buildId"]!.DeepClone(), ["buildStarted"]=false };
        UpdateContract.Write(At("transaction.json"),transaction);
        Phase("switching","正在更新并重启 Linux 服务");
        await Command("stop","service");
        transaction["buildStarted"]=true; UpdateContract.Write(At("transaction.json"),transaction);
        foreach (var file in Directory.GetFiles(stage,"*",SearchOption.AllDirectories)) {
            var target = Path.Combine(output,Path.GetRelativePath(stage,file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!); File.Copy(file,target,true);
        }
        var expected = BuildId(Path.Combine(output,"WebApplication.dll"));
        await Command("start","service");
        Phase("verifying","核对 Linux 版本、身份和 bridge");
        await Verify(job.Sequence,expected); await WarmBridge();
        Phase("completed","Linux 服务端更新完成"); File.Delete(At("transaction.json"));
    }
}
