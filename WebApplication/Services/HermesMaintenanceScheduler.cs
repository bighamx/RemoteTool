using System.Runtime.InteropServices;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services;

/// <summary>Register from the service, run as the logged-on user without password prompts.</summary>
internal static class HermesMaintenanceScheduler
{
    public static void Start(string python, string script, string request)
    {
        if (!OperatingSystem.IsWindows()) throw new InvalidOperationException("系统维护任务当前仅支持 Windows");
        foreach (var path in new[] { python, script, request })
            if (!Path.IsPathFullyQualified(path) || path.Contains('"') || !File.Exists(path)) throw new InvalidOperationException("维护任务文件不存在或路径无效");
        var objects = new List<object>();
        dynamic Keep(object value) { objects.Add(value); return value; }
        dynamic service = Keep(Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service", true)!)!);
        dynamic folder = null;
        var name = "RemoteTool_Hermes_" + Path.GetFileName(Path.GetDirectoryName(request));
        var created = false;
        try {
            service.Connect();
            folder = Keep(service.GetFolder("\\"));
            dynamic definition = Keep(service.NewTask(0));
            dynamic principal = Keep(definition.Principal);
            var sid = InteractiveProcessLauncher.GetInteractiveUserSid();
            principal.UserId = sid;
            principal.LogonType = 3; // TASK_LOGON_INTERACTIVE_TOKEN; no password and no UAC prompt.
            principal.RunLevel = 0;
            dynamic settings = Keep(definition.Settings);
            settings.Enabled = true; settings.Hidden = true; settings.AllowDemandStart = true;
            settings.DisallowStartIfOnBatteries = false; settings.StopIfGoingOnBatteries = false;
            settings.ExecutionTimeLimit = "PT2H";
            settings.MultipleInstances = 2;
            dynamic actions = Keep(definition.Actions);
            dynamic action = Keep(actions.Create(0));
            action.Path = python;
            action.Arguments = "\"" + script + "\" run \"" + request + "\"";
            action.WorkingDirectory = Path.GetDirectoryName(request);
            // On-demand only: there is no future trigger that could repeat an update.
            dynamic registered = Keep(folder.RegisterTaskDefinition(name, definition, 2, sid, null, 3, null));
            created = true;
            Keep(registered.Run(null));
        } catch (Exception error) when (error is COMException or UnauthorizedAccessException or System.ComponentModel.Win32Exception) {
            throw new InvalidOperationException($"系统维护任务启动失败（0x{error.HResult:X8}）：{error.Message}", error);
        } finally {
            // Deleting the registration does not stop an already-running task action.
            if (created) { try { folder.DeleteTask(name, 0); } catch { } }
            for (var index = objects.Count - 1; index >= 0; index--)
                if (Marshal.IsComObject(objects[index])) Marshal.FinalReleaseComObject(objects[index]);
        }
    }
}
