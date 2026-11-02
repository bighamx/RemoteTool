using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services.Updates;

public sealed record SystemdService(string Unit, string Directory, string KillMode, int Pid, bool UserService);

internal static class SystemdControl
{
    public static void ValidateUnit(string unit) {
        if (!Regex.IsMatch(unit, @"^[a-zA-Z0-9_.@:-]+\.service$")) throw new IOException("systemd 服务名称无效");
    }
    [DllImport("libc")] private static extern uint geteuid();
    public static async Task VerifyControl(bool user, string unit) {
        ValidateUnit(unit);
        if (await Run(user,"show",unit,"--property=KillMode","--value") != "process") throw new IOException("systemd KillMode 必须为 process，网站未停止");
        if (user || geteuid() == 0) return;
        foreach (var action in new[] { "stop", "start" }) {
            var info=new ProcessStartInfo("/usr/bin/sudo") {UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true};
            foreach(var argument in new[]{"-n","-l","--","/usr/bin/systemctl",action,unit})info.ArgumentList.Add(argument);
            using var process=Process.Start(info)!;var output=process.StandardOutput.ReadToEndAsync();var errors=process.StandardError.ReadToEndAsync();
            await process.WaitForExitAsync();await output;await errors;
            if(process.ExitCode!=0)throw new IOException("服务端身份缺少该 systemd 服务的非交互管理权限，网站未停止");
        }
    }
    public static async Task<string> Run(bool user, params string[] arguments) {
        var mutate = arguments[0] is "start" or "stop" or "restart";
        var info = new ProcessStartInfo(mutate && !user && geteuid() != 0 ? "/usr/bin/sudo" : "/usr/bin/systemctl") {
            UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true
        };
        if (info.FileName.EndsWith("sudo")) { info.ArgumentList.Add("-n"); info.ArgumentList.Add("--"); info.ArgumentList.Add("/usr/bin/systemctl"); }
        if (user) info.ArgumentList.Add("--user");
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        using var process = Process.Start(info) ?? throw new IOException("无法调用 systemd");
        var output = process.StandardOutput.ReadToEndAsync(); var errors = process.StandardError.ReadToEndAsync();
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        try { await process.WaitForExitAsync(deadline.Token); }
        catch { if(!process.HasExited)process.Kill(true); throw; }
        var result = await output; await errors;
        if (process.ExitCode != 0) throw new IOException("systemd 操作失败：" + arguments[0] + "；请核对服务名称及当前服务身份的管理权限");
        return result.Trim();
    }
    public static SystemdService Read(string unit, bool user) {
        ValidateUnit(unit);
        var values = Run(user, "show", unit, "--property=WorkingDirectory,KillMode,MainPID").GetAwaiter().GetResult()
            .Split('\n').Select(line => line.Split('=',2)).Where(parts => parts.Length == 2).ToDictionary(parts => parts[0], parts => parts[1].Trim());
        return new(unit, values.GetValueOrDefault("WorkingDirectory", ""), values.GetValueOrDefault("KillMode", ""), int.Parse(values.GetValueOrDefault("MainPID","0")), user);
    }
}
