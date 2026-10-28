using System.Diagnostics;
using RemoteTool.WebApi.Services.RemoteControl;

internal static class MacImagePreviewChecks
{
    public static void Run(FileService service, string root) {
        if (!OperatingSystem.IsMacOS()) return;
        string Sips(params string[] args) {
            var start = new ProcessStartInfo("/usr/bin/sips") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var arg in args) start.ArgumentList.Add(arg);
            using var process = Process.Start(start)!;
            var output = process.StandardOutput.ReadToEnd(); var error = process.StandardError.ReadToEnd();
            if (!process.WaitForExit(5000) || process.ExitCode != 0) throw new Exception(error);
            return output;
        }
        var bmp = Path.Combine(root, "source.bmp");
        using (var writer = new BinaryWriter(File.Create(bmp))) {
            writer.Write((ushort)0x4d42); writer.Write(54 + 120 * 30 * 3); writer.Write(0); writer.Write(54);
            writer.Write(40); writer.Write(120); writer.Write(30); writer.Write((ushort)1); writer.Write((ushort)24);
            writer.Write(0); writer.Write(120 * 30 * 3); writer.Write(0); writer.Write(0); writer.Write(0); writer.Write(0);
            writer.Write(new byte[120 * 30 * 3]);
        }
        var png = Path.Combine(root, "中文 ' $HOME (preview).png");
        Sips("-s", "format", "png", bmp, "--out", png);
        var original = File.ReadAllBytes(png);
        foreach (var size in new[] { (Width: 64, Height: 16), (Width: 1920, Height: 1080) }) {
            var bytes = service.GetImagePreview(png, size.Width, size.Height);
            if (bytes.Length < 4 || bytes[0] != 0xff || bytes[1] != 0xd8) throw new Exception("PNG previews must produce actual JPEG bytes on Mac");
            var jpeg = Path.Combine(root, "preview.jpg"); File.WriteAllBytes(jpeg, bytes);
            var dimensions = Sips("-g", "pixelWidth", "-g", "pixelHeight", jpeg);
            var expectedWidth = Math.Min(size.Width, 120); var expectedHeight = Math.Min(size.Height, 30);
            if (!dimensions.Contains($"pixelWidth: {expectedWidth}") || !dimensions.Contains($"pixelHeight: {expectedHeight}"))
                throw new Exception("Mac preview must preserve aspect ratio, fit bounds and avoid upscaling");
        }
        if (!File.ReadAllBytes(png).SequenceEqual(original)) throw new Exception("Preview must preserve the source image");
        if (service.GetImagePreview(Path.Combine(root, "missing.png")).Length != 0) throw new Exception("Missing preview must return empty");
        Console.WriteLine("Mac image previews passed: native decode, real JPEG, resize, Unicode/quoted paths, no source edits");
    }
}
