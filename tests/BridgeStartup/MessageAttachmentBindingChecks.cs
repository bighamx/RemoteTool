using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Mvc;

internal static class MessageAttachmentBindingChecks
{
    public static void Run(Assembly assembly)
    {
        var root = Path.Combine(Path.GetTempPath(), "remotetool-binding-test-" + Guid.NewGuid().ToString("N"));
        var session = "binding-session";
        Directory.CreateDirectory(Path.Combine(root, session));
        try
        {
            var attachmentsType = assembly.GetType("RemoteTool.WebApi.Services.HermesAttachments", true)!;
            var attachments = attachmentsType.GetConstructor(BindingFlags.Instance | BindingFlags.NonPublic, null,
                [typeof(string), typeof(string)], null)!.Invoke(["codex", root]);
            var controllerType = assembly.GetType("RemoteTool.WebApi.Controllers.CodexController", true)!;
            var controller = Activator.CreateInstance(controllerType, [null, attachments, null, null])!;
            IActionResult Bind(string message, object body) => (IActionResult)controllerType.GetMethod("Bind")!.Invoke(controller,
                [session, message, JsonSerializer.SerializeToElement(body)])!;
            var missing = Bind("123", new { ids = new[] { new string('a', 64) } });
            if (missing is not NotFoundObjectResult { Value: { } error } ||
                JsonSerializer.SerializeToNode(error)?["code"]?.ToString() != "attachment_missing")
                throw new Exception("A missing attachment must return a typed 404 instead of throwing HTTP 500");
            if (Bind("123", new { ids = "invalid" }) is not BadRequestObjectResult ||
                Bind("0", new { ids = Array.Empty<string>() }) is not BadRequestObjectResult)
                throw new Exception("Malformed attachment bindings must be rejected");
            File.WriteAllText(Path.Combine(root, session, "existing.txt"), "preserved");
            var catalog = (object[])attachmentsType.GetMethod("List")!.Invoke(attachments, [session])!;
            var id = JsonSerializer.SerializeToNode(catalog)![0]!["id"]!.ToString();
            if (Bind("123", new { ids = new[] { id } }) is not OkObjectResult)
                throw new Exception("An existing attachment must still bind normally");
            var bindingPath = Path.Combine(root, session, ".chuckie-message-attachments.json");
            var original = File.ReadAllText(bindingPath);
            Bind("123", new { ids = new[] { id, new string('b', 64) } });
            if (File.ReadAllText(bindingPath) != original)
                throw new Exception("Missing attachments must preserve the previous binding atomically");
            Console.WriteLine("PASS attachment binding controller: typed missing-file response, input validation, existing files and atomic preservation");
        }
        finally { Directory.Delete(root, true); }
    }
}
