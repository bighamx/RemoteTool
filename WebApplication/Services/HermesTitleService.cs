using System.Text.Json;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services;

public interface ITitleModelGateway { Task<JsonObject> TitleRequest(string path, JsonObject body, CancellationToken ct); }

public sealed class HermesTitleService {
    private readonly HermesBridge bridge;
    private readonly CodexTitleGenerator titles;
    private readonly Func<string, JsonObject, CancellationToken, Task<JsonObject>> titleRequest;
    public HermesTitleService(HermesBridge bridge, ITitleModelGateway gateway) : this(bridge, RemoteToolPaths.CodexBridge) { titleRequest = gateway.TitleRequest; }
    internal HermesTitleService(HermesBridge bridge, string folder) {
        this.bridge = bridge; titles = new(folder, "hermes");
        titleRequest = async (path, body, ct) => path.EndsWith("/models") ? await titles.Models(body, ct) : Obj(("title", await titles.Generate(body.S("input", "修复手机会话的消息顺序与状态显示"), ct)));
    }
    private readonly SemaphoreSlim rename = new(1, 1);
    public JsonObject Config() => titles.PublicConfig();
    public JsonObject Save(JsonObject body) => titles.Save(body);
    public Task<JsonObject> Models(JsonObject body, CancellationToken ct) => titleRequest("title-model/models", body, ct);
    public Task<JsonObject> Test(CancellationToken ct) => titleRequest("title-model/test", new(), ct);
    public void Register(string id, string title, bool automatic) => titles.Register(id, title, automatic);
    public void Accepted(string id, string input) {
        try { if (titles.Claim(id)) _ = Generate(id, input); } catch (IOException) { }
    }
    public async Task<HttpResponseMessage> Rename(string id, JsonElement body, CancellationToken ct) {
        await rename.WaitAsync(ct);
        try {
            var response = await bridge.SendAsync(HttpMethod.Patch, "api/sessions/" + id, body, null, ct);
            if (response.IsSuccessStatusCode) titles.Manual(id);
            return response;
        } finally { rename.Release(); }
    }
    private async Task Generate(string id, string input) {
        try {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(40));
            var title = (await titleRequest("title-model/generate", Obj(("input", input)), timeout.Token)).S("title");
            await rename.WaitAsync();
            try {
                using var info = await bridge.SendAsync(HttpMethod.Get, "api/sessions/" + id, null, null, CancellationToken.None);
                if (!info.IsSuccessStatusCode) { titles.Finish(id, false); return; }
                var value = JsonNode.Parse(await info.Content.ReadAsStringAsync());
                var current = value?["session"] ?? value;
                if (!titles.CanApply(id, current.S("title"))) { titles.Finish(id, false); return; }
                using var changed = await bridge.SendAsync(HttpMethod.Patch, "api/sessions/" + id, JsonSerializer.SerializeToElement(new { title }), null, CancellationToken.None);
                titles.Finish(id, changed.IsSuccessStatusCode);
            } finally { rename.Release(); }
        } catch (Exception error) when (error is CodexError or IOException or HttpRequestException or InvalidOperationException or OperationCanceledException or JsonException) {
            try { titles.Finish(id, false); } catch (IOException) { }
        }
    }
}
