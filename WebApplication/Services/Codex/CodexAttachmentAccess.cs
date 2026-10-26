using System.Security.AccessControl;
using System.Security.Principal;

namespace RemoteTool.WebApi.Services.Codex;

/// <summary>The Web host writes uploads; the interactive Codex worker must be able to read them.</summary>
internal static class CodexAttachmentAccess
{
    public static void Ensure(string root, string userSid)
    {
        if (!OperatingSystem.IsWindows()) return;
        Directory.CreateDirectory(root);
        var directory = new DirectoryInfo(root);
        if ((directory.Attributes & FileAttributes.ReparsePoint) != 0)
            throw new IOException("附件目录不能是链接目录");
        var acl = directory.GetAccessControl(AccessControlSections.Access);
        var changed = false;
        foreach (var sid in new[] { userSid, "S-1-5-18" }.Distinct()) {
            var identity = new SecurityIdentifier(sid);
            var inherited = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
            if (acl.GetAccessRules(true, true, typeof(SecurityIdentifier)).OfType<FileSystemAccessRule>().Any(rule =>
                rule.IdentityReference.Equals(identity) && rule.AccessControlType == AccessControlType.Allow &&
                (rule.FileSystemRights & FileSystemRights.FullControl) == FileSystemRights.FullControl &&
                (rule.InheritanceFlags & inherited) == inherited && rule.PropagationFlags == PropagationFlags.None)) continue;
            acl.AddAccessRule(new FileSystemAccessRule(identity, FileSystemRights.FullControl, inherited,
                PropagationFlags.None, AccessControlType.Allow));
            changed = true;
        }
        if (changed) directory.SetAccessControl(acl);
    }
}
