using System.Net.Sockets;
using System.Runtime.InteropServices;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexMacDesktopChecks
{
    public static async Task Run() {
        if (!OperatingSystem.IsMacOS()) return;
        var home = Path.Combine("/tmp", "rc-mac-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path.Combine(home, "thread-writer-locks"));
        Directory.CreateDirectory(Path.Combine(home, "ipc"));
        try {
            var excluded = System.Diagnostics.Process.GetProcesses().Select(process => { using (process) return process.Id; }).ToList();
            var executable = Path.Combine(home, "codex");
            File.Copy("/bin/sleep", executable);
            using (var signer = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("/usr/bin/codesign") {
                ArgumentList = { "--force", "--sign", "-", executable }, UseShellExecute = false, RedirectStandardError = true
            })!) {
                await signer.WaitForExitAsync();
                if (signer.ExitCode != 0) throw new Exception("Cannot sign the isolated process fixture");
            }
            using (var cli = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(executable, "60") { UseShellExecute = false })!) {
                try {
                    await Task.Delay(100);
                    if (!CodexAccountStore.DesktopBusy(excluded)) throw new Exception("Mac must detect another Codex process before switching identity");
                    excluded.Add(cli.Id);
                    if (CodexAccountStore.DesktopBusy(excluded)) throw new Exception("Bridge-owned processes must be excluded from desktop detection");
                } finally { if (!cli.HasExited) cli.Kill(); await cli.WaitForExitAsync(); }
            }
            var id = Guid.NewGuid().ToString();
            var rollout = Path.Combine(home, "rollout-" + id + ".jsonl");
            var lease = Path.Combine(home, "thread-writer-locks", id + ".lock");
            File.WriteAllText(rollout, ""); File.WriteAllText(lease, "");
            using (var writer = new FileStream(lease, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite)) {
                var fd = writer.SafeFileHandle.DangerousGetHandle().ToInt32();
                if (flock(fd, 2 | 4) != 0) throw new Exception("Cannot acquire test writer lock");
                try {
                    if (!CodexRollout.WriterLocked(home, rollout)) throw new Exception("Mac native writer must be detected");
                    if (CodexRollout.WriterLocked(home, Path.Combine(home, "..", "rollout-" + id + ".jsonl")))
                        throw new Exception("Writer probe must stay inside its Codex home");
                } finally { flock(fd, 8); }
            }
            if (CodexRollout.WriterLocked(home, rollout)) throw new Exception("Released Mac writer must not remain busy");

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            using var listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            listener.Bind(new UnixDomainSocketEndPoint(Path.Combine(home, "ipc", "ipc.sock"))); listener.Listen(1);
            var accepted = listener.AcceptAsync(timeout.Token).AsTask();
            try {
                await new CodexDesktopSync(home).StartTurn(id, new());
                throw new Exception("A same-user impostor socket must not accept desktop operations");
            } catch (CodexError error) when (error.Code == "session_owner_unavailable") { }
            using var peer = await accepted;
            var bytes = await peer.ReceiveAsync(new byte[1], SocketFlags.None, timeout.Token);
            if (bytes != 0) throw new Exception("Untrusted socket must not receive prompts or handshake data");
            Console.WriteLine("Mac writer lock and desktop peer identity checks passed");
        } finally { Directory.Delete(home, true); }
    }
    [DllImport("libc", SetLastError = true)] private static extern int flock(int fd, int operation);
}
