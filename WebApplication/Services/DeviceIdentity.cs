using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;

namespace ChuckieHelper.WebApi.Services;

/// <summary>Stable, opaque identity for the physical host, independent of endpoint and web installation.</summary>
public sealed class DeviceIdentity
{
    public string Id { get; }

    public DeviceIdentity()
    {
        string? machineId = null;
        if (OperatingSystem.IsWindows())
            machineId = Registry.GetValue(@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Cryptography", "MachineGuid", null) as string;
        else if (File.Exists("/etc/machine-id"))
            machineId = File.ReadAllText("/etc/machine-id").Trim();

        // The fallback lives outside the web app directory, so deploying another copy does not clone its identity.
        if (string.IsNullOrWhiteSpace(machineId))
        {
            var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ChuckieHelper");
            Directory.CreateDirectory(directory);
            var path = Path.Combine(directory, "device-id");
            if (!File.Exists(path))
            {
                try { using var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write); file.Write(Encoding.UTF8.GetBytes(Guid.NewGuid().ToString("N"))); }
                catch (IOException) { /* Another process created it. */ }
            }
            machineId = File.ReadAllText(path).Trim();
        }

        Id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("ChuckieHelper/device/v1/" + machineId))).ToLowerInvariant();
    }
}
