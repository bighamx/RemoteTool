using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class TitleModelChecks {
    public static async Task Run() {
        var folder = Path.Combine(Path.GetTempPath(), "title-tests-" + Guid.NewGuid());
        Directory.CreateDirectory(folder);
        try {
            var generator = new CodexTitleGenerator(folder);
            var socket = new TcpListener(IPAddress.Loopback, 0); socket.Start();
            var port = ((IPEndPoint)socket.LocalEndpoint).Port; socket.Stop();
            using var listener = new HttpListener(); listener.Prefixes.Add($"http://127.0.0.1:{port}/"); listener.Start();
            var config = new JsonObject { ["enabled"] = true, ["base_url"] = $"http://127.0.0.1:{port}/v1", ["model"] = "cheap-model", ["api_key"] = "test-private-key", ["api_mode"] = "chat_completions" };
            void Check(bool result) { if (!result) throw new Exception("Title model contract failed"); }
            Check(CodexTitleGenerator.DirectTitle("  小鸡游戏 \n 测试 ") == "小鸡游戏 测试");
            Check(CodexTitleGenerator.DirectTitle(new string('中', 19)) == new string('中', 19));
            Check(CodexTitleGenerator.DirectTitle(new string('中', 20)) == null);
            var emojiTitle = string.Concat(Enumerable.Repeat("😀", 19));
            Check(CodexTitleGenerator.DirectTitle(emojiTitle) == emojiTitle);
            Check(CodexTitleGenerator.DirectTitle("  \n ") == null);
            config["prompt"] = "Custom concise title instruction";
            var saved = generator.Save(config);
            Check(saved["prompt"].ToString() == "Custom concise title instruction");
            Check(saved["api_key"] == null && saved["has_api_key"].GetValue<bool>());
            generator.Save(new JsonObject { ["api_key"] = "" });
            generator.Register("new", "default", true); Check(generator.Claim("new") && !generator.Claim("new"));
            Check(!new CodexTitleGenerator(folder).Claim("new"));
            var generate = generator.Generate(new string('中', 900));
            var request = await listener.GetContextAsync().WaitAsync(TimeSpan.FromSeconds(5));
            var body = JsonNode.Parse(await new StreamReader(request.Request.InputStream).ReadToEndAsync());
            Check(request.Request.RawUrl == "/v1/chat/completions" && request.Request.Headers["Authorization"] == "Bearer test-private-key");
            Check(body["model"].ToString() == "cheap-model" && body["max_tokens"].GetValue<int>() == 64 && body["messages"][1]["content"].ToString().Length == 600);
            Check(body["messages"][0]["content"].ToString() == "Custom concise title instruction");
            var bytes = Encoding.UTF8.GetBytes("{\"choices\":[{\"message\":{\"content\":\"手机会话修复\"}}]}");
            request.Response.ContentType = "application/json"; request.Response.ContentLength64 = bytes.Length;
            await request.Response.OutputStream.WriteAsync(bytes); request.Response.Close();
            Check(await generate == "手机会话修复");
            Check(generator.CanApply("new", "default") && !generator.CanApply("new", "edited-on-desktop"));
            generator.Manual("new"); Check(!generator.CanApply("new", "default"));
            generator.Finish("new", true); Check(generator.State("new")["status"].ToString() == "manual");
            generator.Save(new JsonObject { ["api_mode"] = "responses", ["base_url"] = $"http://127.0.0.1:{port}/v1/responses" });
            generate = generator.Generate("short input"); request = await listener.GetContextAsync().WaitAsync(TimeSpan.FromSeconds(5));
            body = JsonNode.Parse(await new StreamReader(request.Request.InputStream).ReadToEndAsync());
            Check(request.Request.RawUrl == "/v1/responses" && body["max_output_tokens"].GetValue<int>() == 64 && !body["store"].GetValue<bool>() && body["input"].ToString() == "short input");
            Check(body["instructions"].ToString() == "Custom concise title instruction");
            bytes = Encoding.UTF8.GetBytes("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"廉价标题\"}]}]}");
            request.Response.ContentLength64 = bytes.Length; await request.Response.OutputStream.WriteAsync(bytes); request.Response.Close();
            Check(await generate == "廉价标题");
            var listing = generator.Models(new JsonObject(), CancellationToken.None);
            request = await listener.GetContextAsync().WaitAsync(TimeSpan.FromSeconds(5));
            Check(request.Request.RawUrl == "/v1/models" && request.Request.HttpMethod == "GET" && request.Request.Headers["Authorization"] == "Bearer test-private-key");
            bytes = Encoding.UTF8.GetBytes("{\"data\":[{\"id\":\"cheap-model\"},{\"id\":\"other-model\"},{\"id\":\"cheap-model\"}]}");
            request.Response.ContentLength64 = bytes.Length; await request.Response.OutputStream.WriteAsync(bytes); request.Response.Close();
            Check((await listing)["models"].AsArray().Count == 2);
            var hermes = new CodexTitleGenerator(folder, "hermes");
            hermes.Register("new", "hermes default", true); Check(hermes.Claim("new"));
            Check(generator.State("new")["status"].ToString() == "manual");
            hermes.Save(new JsonObject { ["prompt"] = "Shared updated prompt" });
            Check(generator.PublicConfig()["prompt"].ToString() == "Shared updated prompt");
            generate = generator.Generate("failure"); request = await listener.GetContextAsync().WaitAsync(TimeSpan.FromSeconds(5));
            request.Response.StatusCode = 401; request.Response.Close();
            try { await generate; throw new Exception("Expected provider failure"); } catch (CodexError error) { Check(error.Code == "title_model_failed" && !error.Message.Contains("test-private-key")); }
            generator.Save(new JsonObject { ["clear_api_key"] = true }); Check(!generator.PublicConfig()["has_api_key"].GetValue<bool>());
            generator.Save(new JsonObject { ["prompt"] = "" }); Check(generator.PublicConfig()["prompt"].ToString() == CodexTitleGenerator.DefaultPrompt);
            Console.WriteLine("Title model checks passed: short direct titles, both transports, input/output budget, no key echo, no replay, manual rename, provider failure");
        } finally { Directory.Delete(folder, true); }
    }
}
