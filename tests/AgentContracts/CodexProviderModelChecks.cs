using System.Net;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

internal static class CodexProviderModelChecks
{
    public static async Task Run() {
        static void Check(bool value, string name) { if (!value) throw new Exception(name); }
        var config = Obj(("model_provider", "openai"), ("model", "gpt-default"));
        var native = Obj(("data", new JsonArray(Obj(("id", "gpt-native"), ("model", "gpt-native")))));
        var mirror = Obj(("name", "OpenAI"), ("requires_openai_auth", true));
        Check(CodexProviderModels.IsOfficial("custom", mirror), "cc-switch official mirror is native OpenAI");
        Check(!CodexProviderModels.IsOfficial("relay", Obj(("name", "OpenAI"), ("requires_openai_auth", true), ("base_url", "https://relay.example/v1"))), "same-name third-party endpoint stays distinct");
        var mirrorConfig = Obj(("model_providers", Obj(("custom", mirror))));
        var mirrorModels = CodexProviderModels.Catalog("custom", mirrorConfig, new JsonObject(), native, null);
        Check(mirrorModels.B("official_provider") && mirrorModels.A("data")[0].S("model") == "gpt-native", "mirror uses complete native catalog");
        var merged = CodexProviderModels.MergeOfficialAliases(new JsonArray(
            Obj(("slug", "custom"), ("official_provider", true)), Obj(("slug", "openai"), ("official_provider", true)), Obj(("slug", "local"))), "custom");
        Check(merged.Count == 2 && merged.Last().S("slug") == "custom" && merged.Last().A("aliases").Count == 2, "one official card preserves active native provider identity");
        var endpoint = Obj(("base_url", "http://127.0.0.1:7890/v1/"));
        var saved = Obj(("model", "glm"), ("api_key", "test-only-key"));
        var handler = new ProbeHandler(request => {
            Check(request.RequestUri!.AbsoluteUri == "http://127.0.0.1:7890/v1/models", "models URL uses provider base");
            Check(request.Headers.Authorization?.ToString() == "Bearer test-only-key", "model discovery uses saved provider credential");
            return new(HttpStatusCode.OK) { Content = new StringContent("""{"data":[{"id":"glm"},{"id":"deepseek"},{"id":"glm"}]}""") };
        });
        using var http = new HttpClient(handler);
        var discovered = await CodexProviderModels.Discover(endpoint, saved, client: http);
        var catalog = CodexProviderModels.Catalog("local", config, saved, native, discovered);
        Check(catalog.A("data").Count == 2, "all provider models, deduplicated");
        Check(!catalog.A("data").Any(m => m.S("model").StartsWith("gpt")), "no unrelated native catalog leakage");
        var selection = CodexModelSettings.Validate(Obj(("provider", "local"), ("model", "deepseek"), ("reasoning_effort", ""), ("service_tier", "priority")), catalog);
        Check(selection.S("reasoningEffort") == "medium" && selection.S("serviceTier") == "default", "custom reasoning has nonempty default and clears inherited fast tier");
        var resume = Obj(("config", Obj(("preserved", true))));
        CodexModelSettings.ApplyResume(resume, selection);
        Check(resume["config"].S("model_reasoning_effort") == "medium" && resume["config"].B("preserved"), "resume retains overrides and never sends null effort");
        var turn = new JsonObject(); CodexModelSettings.ApplyTurn(turn, selection, new());
        Check(turn.S("effort") == "medium" && turn.S("serviceTier") == "default", "custom selection reaches next turn");
        var old = Obj(("model", "glm"), ("modelProvider", "local"), ("reasoningEffort", null));
        resume = new(); CodexModelSettings.ApplyResume(resume, old);
        Check(resume["config"] == null, "legacy null selection does not poison TOML");
        try {
            CodexModelSettings.Validate(Obj(("provider", "local"), ("model", "gpt-native")), catalog);
            throw new Exception("wrong provider model accepted");
        } catch (CodexError) { }
        using var denied = new HttpClient(new ProbeHandler(_ => new(HttpStatusCode.Unauthorized)));
        var failure = await CodexProviderModels.Discover(endpoint, saved, client: denied);
        var fallback = CodexProviderModels.Catalog("local", config, saved, native, failure);
        Check(fallback.A("data").Count == 1 && fallback.A("data")[0].S("model") == "glm" && fallback.S("models_warning").Contains("401"), "failed discovery reports error and only retains manual model");
        Check(!fallback.ToJsonString().Contains("test-only-key"), "public catalog never exposes credentials");
        Check(CodexProviderModels.Endpoint("http://localhost/v1/responses").AbsolutePath == "/v1/models", "full Responses endpoint normalized");
        var paths = new List<string>();
        using var versioned = new HttpClient(new ProbeHandler(request => {
            paths.Add(request.RequestUri!.AbsolutePath);
            return paths.Count == 1 ? new(HttpStatusCode.NotFound) : new(HttpStatusCode.OK) { Content = new StringContent("""{"data":[{"id":"glm"}]}""") };
        }));
        var resolved = await CodexProviderModels.Discover(Obj(("base_url", "http://localhost:8788")), saved, client: versioned);
        Check(paths.SequenceEqual(new[] { "/models", "/v1/models" }) && resolved.S("base_url") == "http://localhost:8788/v1", "root-address 404 retries standard v1 endpoint");
        var resolvedCatalog = CodexProviderModels.Catalog("local", config, saved, native, resolved);
        var resolvedSelection = CodexModelSettings.Validate(Obj(("provider", "local"), ("model", "glm"), ("reasoning_effort", "high")), resolvedCatalog);
        resume = new(); CodexModelSettings.ApplyResume(resume, resolvedSelection);
        Check(resume["config"].S("model_providers.local.base_url") == "http://localhost:8788/v1", "model discovery and generation use same resolved base URL");
        Check(CodexProviderModels.Catalog("openai", config, saved, native, discovered).A("data").Count == 1, "official catalog remains native");
        var metadataCatalog = CodexProviderModels.Normalize(JsonNode.Parse("""{"data":[{"id":"glm","context_length":1000000,"max_output_tokens":131072,"supports_images":true,"supports_reasoning":true},{"id":"small","context_window":32768},{"id":"unknown","context_length":-1}]}"""));
        Check(metadataCatalog[0].L("context_window") == 1_000_000 && metadataCatalog[0].L("max_output_tokens") == 131072 && metadataCatalog[0].B("supports_images"), "discovery retains declared model capacity and capabilities");
        Check(metadataCatalog[1].L("context_window") == 32768 && CodexRuntimeCatalog.Window(metadataCatalog[2]) == 0, "alternate capacity field and invalid capacity");
        var nativeBase = Obj(("models", new JsonArray(Obj(("slug", "official"), ("context_window", 272000)), Obj(("slug", "glm"), ("context_window", 272000)))));
        var combined = CodexRuntimeCatalog.Build(nativeBase, "glm", metadataCatalog[0]!.AsObject());
        var registered = combined.A("models").Single(m => m.S("slug") == "glm");
        Check(combined.A("models").Count == 2 && registered.L("context_window") == 1_000_000 && registered.L("max_context_window") == 1_000_000 && registered.L("effective_context_window_percent") == 95, "native directory replaces fallback ceiling and retains native reserve");
        Check(nativeBase.A("models")[1].L("context_window") == 272000, "source model cache remains untouched");
        var metadataSelection = CodexModelSettings.Validate(Obj(("provider", "local"), ("model", "glm")), Obj(("data", metadataCatalog)));
        resume = new(); CodexModelSettings.ApplyResume(resume, metadataSelection);
        Check(resume["config"].L("model_context_window") == 1_000_000 && resume["config"].L("model_auto_compact_token_limit") == 900000, "resumed thread carries supplier window and bounded compaction limit");
        var unknownConfig = new JsonObject(); CodexRuntimeCatalog.ApplyContext(unknownConfig, metadataCatalog[2]!.AsObject());
        Check(unknownConfig.Count == 0, "missing supplier capacity is never invented");
        var directory = Path.Combine(Path.GetTempPath(), "codex-context-source-" + Guid.NewGuid().ToString("N"));
        var sessions = Path.Combine(directory, "sessions"); Directory.CreateDirectory(sessions);
        var id = Guid.NewGuid().ToString(); var normal = Path.Combine(sessions, "rollout-2026-10-18-" + id + ".jsonl");
        try {
            File.WriteAllText(normal, "");
            Check(CodexPreviewRollout.LatestPath(directory, id, "") == normal, "context finds normal rollout when thread/read omits path");
            Check(CodexPreviewRollout.LatestPath(directory, id, "", requireAlias: true) == "", "preview alias path never substitutes normal rollout");
        } finally { Directory.Delete(directory, true); }
        Console.WriteLine("Codex provider model checks passed: scoped discovery, authentication, fallback, reasoning, resume and turn");
    }
    private sealed class ProbeHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => Task.FromResult(respond(request));
    }
}
