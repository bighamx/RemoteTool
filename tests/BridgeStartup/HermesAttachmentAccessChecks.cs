using System.Net;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Extensions.Configuration;

internal static class HermesAttachmentAccessChecks
{
    private sealed class Handler(string image) : HttpMessageHandler {
        public int Requests { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) {
            if (File.ReadAllText(image) != "isolated image fixture") throw new Exception("The gateway received its request before the image became readable");
            Requests++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{}") });
        }
    }
    private sealed class Clients(HttpClient client) : IHttpClientFactory {
        public HttpClient CreateClient(string name) => client;
    }
    public static async Task Run(Assembly assembly) {
        if (!OperatingSystem.IsWindows()) { Console.WriteLine("SKIP: Windows Hermes attachment ACL checks"); return; }
        var root = Path.Combine(Path.GetTempPath(), "remotetool-hermes-attachment-acl-" + Guid.NewGuid().ToString("N"));
        var attachmentRoot = Path.Combine(root, "hermes-attachments");
        var session = Path.Combine(attachmentRoot, "fixture-session");
        var image = Path.Combine(session, "uploaded.png");
        Directory.CreateDirectory(session);
        File.WriteAllText(image, "isolated image fixture");
        var keyFile = Path.Combine(root, "api-key.env");
        File.WriteAllText(keyFile, "API_SERVER_KEY=isolated-fixture-key");
        var directory = new DirectoryInfo(attachmentRoot);
        var original = directory.GetAccessControl();
        var current = WindowsIdentity.GetCurrent().User!;
        var limited = new DirectorySecurity();
        limited.SetAccessRuleProtection(true, false);
        limited.AddAccessRule(new FileSystemAccessRule(current,
            FileSystemRights.ReadPermissions | FileSystemRights.ReadAttributes | FileSystemRights.Traverse,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        var oldData = Environment.GetEnvironmentVariable("REMOTETOOL_DATA_DIR");
        try {
            directory.SetAccessControl(limited);
            var denied = false;
            try { File.ReadAllText(image); } catch (UnauthorizedAccessException) { denied = true; }
            if (!denied) throw new Exception("The fixture must reproduce the worker's denied image read");
            Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", root);
            var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string> { ["Hermes:KeyFile"] = keyFile }).Build();
            using var handler = new Handler(image); using var client = new HttpClient(handler);
            var type = assembly.GetType("RemoteTool.WebApi.Services.HermesBridge", true)!;
            var bridge = Activator.CreateInstance(type, new Clients(client), configuration)!;
            async Task Send() {
                using var response = await (Task<HttpResponseMessage>)type.GetMethod("SendAsync")!.Invoke(bridge,
                    [HttpMethod.Get, "api/sessions", null, null, CancellationToken.None, null])!;
                if (!response.IsSuccessStatusCode) throw new Exception("Hermes transport was interrupted by ACL repair");
            }
            await Send();
            if (File.ReadAllText(image) != "isolated image fixture") throw new Exception("Existing uploaded images were not made readable");
            var output = Path.Combine(session, "outbox"); Directory.CreateDirectory(output);
            File.WriteAllText(Path.Combine(output, "generated.txt"), "native output");
            var repaired = directory.GetAccessControl().GetSecurityDescriptorSddlForm(AccessControlSections.Access);
            await Send();
            if (repaired != directory.GetAccessControl().GetSecurityDescriptorSddlForm(AccessControlSections.Access)) throw new Exception("Repeated Hermes requests changed an already repaired ACL");
            if (handler.Requests != 2) throw new Exception("Hermes requests were not forwarded normally");
            Console.WriteLine("PASS: public Hermes requests repair denied existing image reads, enable native output and preserve ACL idempotency");
        } finally {
            directory.SetAccessControl(original);
            Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", oldData);
            Directory.Delete(root, true);
        }
    }
}
