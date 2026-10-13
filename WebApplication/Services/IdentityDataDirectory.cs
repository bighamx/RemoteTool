using System.Security.AccessControl;
using System.Security.Principal;

namespace RemoteTool.WebApi.Services;

/// <summary>Keep login policy files writable only by the hosting identity and local administrators.</summary>
internal static class IdentityDataDirectory
{
    public static void Ensure(string directory)
    {
        Directory.CreateDirectory(directory);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            return;
        }
        var rules = new DirectorySecurity();
        rules.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var sid in TrustedSids())
            rules.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(directory).SetAccessControl(rules);
    }

    public static void EnsureFile(string path)
    {
        if (!File.Exists(path)) return;
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            return;
        }
        var rules = new FileSecurity();
        rules.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var sid in TrustedSids())
            rules.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl, AccessControlType.Allow));
        new FileInfo(path).SetAccessControl(rules);
    }

    private static IEnumerable<SecurityIdentifier> TrustedSids()
    {
        yield return WindowsIdentity.GetCurrent().User
            ?? throw new UnauthorizedAccessException("Cannot identify the OIDC configuration owner");
        yield return new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
        yield return new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
    }
}
