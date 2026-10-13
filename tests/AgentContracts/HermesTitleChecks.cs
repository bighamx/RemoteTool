using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Configuration;
using RemoteTool.WebApi.Services;

internal static class HermesTitleChecks {
    private sealed class Factory(HttpMessageHandler handler) : IHttpClientFactory { public HttpClient CreateClient(string name) => new(handler, false); }
    private sealed class Upstream : HttpMessageHandler {
        public string Title = "default title";
        public readonly TaskCompletionSource<bool> Applied = new(TaskCreationOptions.RunContinuationsAsynchronously);
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            JsonObject result;
            if (request.RequestUri.AbsolutePath == "/api/sessions/test") {
                if (request.Method == HttpMethod.Patch) { Title = JsonNode.Parse(await request.Content.ReadAsStringAsync(ct))["title"].ToString(); Applied.TrySetResult(true); }
                result = new() { ["session"] = new JsonObject { ["id"] = "test", ["title"] = Title } };
            } else throw new Exception("Unexpected Hermes request");
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(result.ToJsonString(), Encoding.UTF8, "application/json") };
        }
    }
    public static async Task Run() {
        var folder = Path.Combine(Path.GetTempPath(), "hermes-title-" + Guid.NewGuid()); Directory.CreateDirectory(folder);
        try {
            var key = Path.Combine(folder, "hermes.env"); await File.WriteAllTextAsync(key, "API_SERVER_KEY=private-test-key");
            var handler = new Upstream();
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string> { ["Hermes:KeyFile"] = key, ["Hermes:BaseUrl"] = "http://127.0.0.1:8642/" }).Build();
            var service = new HermesTitleService(new HermesBridge(new Factory(handler), config), folder);
            using var listener = new HttpListener();
            var socket = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0); socket.Start(); var port = ((IPEndPoint)socket.LocalEndpoint).Port; socket.Stop();
            listener.Prefixes.Add($"http://127.0.0.1:{port}/"); listener.Start();
            service.Save(new JsonObject { ["enabled"] = true, ["base_url"] = $"http://127.0.0.1:{port}/v1", ["model"] = "cheap", ["prompt"] = "title only" });
            service.Register("test", handler.Title, true); service.Accepted("test", "Hermes test user input"); service.Accepted("test", "duplicate");
            var request = await listener.GetContextAsync().WaitAsync(TimeSpan.FromSeconds(5));
            var body = JsonNode.Parse(await new StreamReader(request.Request.InputStream).ReadToEndAsync());
            if (body["messages"][1]["content"].ToString() != "Hermes test user input") throw new Exception("Wrong Hermes title input");
            var bytes = Encoding.UTF8.GetBytes("{\"choices\":[{\"message\":{\"content\":\"Hermes标题修复\"}}]}");
            request.Response.ContentLength64 = bytes.Length; await request.Response.OutputStream.WriteAsync(bytes); request.Response.Close();
            await handler.Applied.Task.WaitAsync(TimeSpan.FromSeconds(5));
            if (handler.Title != "Hermes标题修复") throw new Exception("Hermes generated title was not applied");
            using var renamed = await service.Rename("test", JsonSerializer.SerializeToElement(new { title = "manual title" }), CancellationToken.None);
            service.Accepted("test", "after manual rename");
            var journal = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(folder, "title-sessions-hermes.json")));
            if (handler.Title != "manual title" || journal["test"]["status"].ToString() != "manual") throw new Exception("Hermes manual title not preserved");
            handler.Title = "untitled";
            service.Register("test", handler.Title, true);
            service.Accepted("test", "  小鸡游戏  ");
            for (var i = 0; i < 30 && handler.Title != "小鸡游戏"; i++) await Task.Delay(100);
            journal = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(folder, "title-sessions-hermes.json")));
            if (handler.Title != "小鸡游戏" || journal["test"]["status"].ToString() != "completed") throw new Exception("Short Hermes input must become the title without a model request");
            Console.WriteLine("Hermes title integration passed: short direct title, generated title, duplicate prevention, manual rename");
        } finally { Directory.Delete(folder, true); }
    }
}
