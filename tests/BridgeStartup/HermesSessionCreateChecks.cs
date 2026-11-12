using System.Net;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;

internal static class HermesSessionCreateChecks
{
    private sealed class Factory(HttpMessageHandler handler) : IHttpClientFactory
    {
        public HttpClient CreateClient(string name) => new(handler, false);
    }

    private sealed class Upstream : HttpMessageHandler
    {
        public readonly List<JsonObject> Bodies = [];
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (request.Method != HttpMethod.Post || request.RequestUri!.AbsolutePath != "/api/sessions")
                throw new Exception("Unexpected Hermes request");
            var body = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!.AsObject();
            Bodies.Add(body);
            var title = body["title"]?.ToString();
            var conflict = title is "新Hermes会话" or "existing manual title";
            var payload = conflict
                ? new JsonObject { ["error"] = new JsonObject { ["code"] = "invalid_title", ["message"] = "Title already in use" } }
                : new JsonObject { ["session"] = new JsonObject { ["id"] = "created-" + Bodies.Count, ["title"] = title } };
            return new HttpResponseMessage(conflict ? HttpStatusCode.BadRequest : HttpStatusCode.Created)
            {
                Content = new StringContent(payload.ToJsonString(), Encoding.UTF8, "application/json")
            };
        }
    }

    public static async Task Run(Assembly assembly)
    {
        var folder = Path.Combine(Path.GetTempPath(), "remotetool-hermes-create-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            var key = Path.Combine(folder, "fixture.env");
            await File.WriteAllTextAsync(key, "API_SERVER_KEY=isolated-fixture-key");
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string>
            {
                ["Hermes:KeyFile"] = key, ["Hermes:BaseUrl"] = "http://127.0.0.1:8642/"
            }).Build();
            var handler = new Upstream();
            var bridgeType = assembly.GetType("RemoteTool.WebApi.Services.HermesBridge", true)!;
            var bridge = Activator.CreateInstance(bridgeType, new Factory(handler), config)!;
            var titleType = assembly.GetType("RemoteTool.WebApi.Services.HermesTitleService", true)!;
            var titles = titleType.GetConstructor(BindingFlags.Instance | BindingFlags.NonPublic, null,
                [bridgeType, typeof(string)], null)!.Invoke([bridge, folder]);
            var gatewayType = assembly.GetType("RemoteTool.WebApi.Services.PhoneControl.PhoneGateway", true)!;
            var gateway = Activator.CreateInstance(gatewayType, Path.Combine(folder, "phone"))!;
            var phoneType = assembly.GetType("RemoteTool.WebApi.Services.PhoneControl.PhoneSessionIntegration", true)!;
            var phone = Activator.CreateInstance(phoneType, gateway, config, null)!;
            var controllerType = assembly.GetType("RemoteTool.WebApi.Controllers.HermesController", true)!;
            var create = controllerType.GetMethod("CreateSession")!;
            async Task<(int Status, JsonObject Payload)> Create(JsonObject body)
            {
                var context = new DefaultHttpContext();
                context.Response.Body = new MemoryStream();
                var controller = (ControllerBase)Activator.CreateInstance(controllerType,
                    bridge, null, null, null, null, null, titles, null)!;
                controller.ControllerContext = new ControllerContext { HttpContext = context };
                await (Task)create.Invoke(controller, [JsonSerializer.SerializeToElement(body), phone, CancellationToken.None])!;
                context.Response.Body.Position = 0;
                var payload = JsonNode.Parse(await new StreamReader(context.Response.Body).ReadToEndAsync())!.AsObject();
                return (context.Response.StatusCode, payload);
            }

            foreach (var title in new string?[] { "新Hermes会话", "新Hermes会话", "手机对话20261112_120000", null, " " })
            {
                var result = await Create(new JsonObject { ["title"] = title, ["auto_title"] = true });
                var sent = handler.Bodies.Last();
                if (result.Status != 201 || sent.ContainsKey("title") || sent.ContainsKey("auto_title"))
                    throw new Exception("Automatic titles must be omitted before native creation, including legacy clients");
            }
            var blank = await Create(new JsonObject { ["title"] = " " });
            var manual = await Create(new JsonObject { ["title"] = "my manual title", ["auto_title"] = false });
            var conflict = await Create(new JsonObject { ["title"] = "existing manual title", ["auto_title"] = false });
            if (blank.Status != 201 || manual.Status != 201 || handler.Bodies[^2]["title"]?.ToString() != "my manual title"
                || conflict.Status != 400 || conflict.Payload["error"]?["code"]?.ToString() != "invalid_title" || handler.Bodies.Count != 8)
                throw new Exception("Blank titles must work and explicit titles and native errors must be preserved without retries");
            var journal = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(folder, "title-sessions-hermes.json")))!;
            if (journal["created-1"]?["status"]?.ToString() != "pending" || journal["created-6"]?["status"]?.ToString() != "pending"
                || journal["created-7"]?["status"]?.ToString() != "manual" || journal["created-8"] != null)
                throw new Exception("Successful creations must retain automatic or manual title registration");
            Console.WriteLine("PASS: Hermes controller creation, repeated automatic titles, legacy App titles, blank/manual titles and rejected duplicates");
        }
        finally { Directory.Delete(folder, true); }
    }
}
