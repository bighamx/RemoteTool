using System.Net;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

internal static class CodexProviderModelChecks
{
    public static async Task Run() {
        static void Check(bool value, string name) { if (!value) throw new Exception(name); }
        var config = Obj(("model_provider", "openai"), ("model", "gpt-default"));
        Check(CodexSessionModelSelection.ManagedProxy(Obj(("experimental_bearer_token", "PROXY_MANAGED"), ("base_url", "http://localhost:45678/v1"))), "CC placeholder detection uses ownership marker rather than port");
        Check(!CodexSessionModelSelection.ManagedProxy(Obj(("name", "custom"), ("base_url", "http://127.0.0.1:15721/v1"))), "ordinary custom endpoints are not classified by address alone");
        var native = Obj(("data", new JsonArray(Obj(("id", "gpt-native"), ("model", "gpt-native")))));
        var mirror = Obj(("name", "OpenAI"), ("requires_openai_auth", true));
        Check(CodexProviderModels.IsOfficial("custom", mirror), "cc-switch official mirror is native OpenAI");
        Check(!CodexProviderModels.IsOfficial("relay", Obj(("name", "OpenAI"), ("requires_openai_auth", true), ("base_url", "https://relay.example/v1"))), "same-name third-party endpoint stays distinct");
        var stale = Obj(("model", "gpt-native"), ("modelProvider", "custom"), ("reasoningEffort", "high"),
            ("serviceTier", "priority"), ("providerBaseUrl", "http://127.0.0.1:15721/v1"),
            ("modelMetadata", Obj(("context_window", 1000000))));
        var routing = Obj(("model", "gpt-native"), ("model_providers", Obj(("custom", Obj(
            ("name", "CC Switch"), ("base_url", "http://127.0.0.1:15721/v1"), ("experimental_bearer_token", "PROXY_MANAGED"))))));
        var repaired = CodexSessionModelSelection.Reconcile(stale, routing, native);
        Check(repaired.S("modelProvider") == "openai" && repaired.S("model") == "gpt-native" && repaired.B("officialProvider"), "dormant CC Switch session resumes with effective official provider");
        Check(!repaired.ContainsKey("providerBaseUrl") && !repaired.ContainsKey("modelMetadata"), "repair removes stale endpoint and third-party runtime capacity");
        Check(repaired.S("reasoningEffort") == "high" && repaired.S("serviceTier") == "priority", "repair preserves valid user model tuning");
        Check(stale.S("modelProvider") == "custom" && CodexSessionModelSelection.RoutingChanged(stale,repaired), "repair is isolated and invalidates the old session owner");
        var actualResume = new JsonObject(); CodexModelSettings.ApplyResume(actualResume,repaired);
        Check(actualResume.S("modelProvider") == "openai" && actualResume["config"]?["model_providers.custom.base_url"] == null, "actual resume no longer injects proxy routing");
        var customModel = stale.DeepClone().AsObject(); customModel["model"] = "third-party-only";
        Check(CodexSessionModelSelection.Reconcile(customModel,routing,native).S("model") == "gpt-native", "provider-specific model cannot leak into official recovery");
        routing["model_provider"] = "custom";
        Check(CodexSessionModelSelection.Reconcile(stale,routing).S("modelProvider") == "custom", "active CC Switch third-party selection remains usable");
        CodexSessionModelSelection.RequireCurrentRequest(Obj(("provider","custom")),routing);
        routing.Remove("model_provider");
        try { CodexSessionModelSelection.RequireCurrentRequest(Obj(("provider","custom")),routing); throw new Exception("stale explicit UI accepted"); }
        catch (CodexError error) { Check(error.Status == 409 && error.Code == "model_settings_changed" && error.Delivery == "rejected", "stale UI is rejected before inference, never silently rerouted"); }
        var relayConfig = Obj(("model_providers", Obj(("relay", Obj(("base_url","https://relay.example/v1"))))));
        var relaySelection = Obj(("model","glm"),("modelProvider","relay"),("providerBaseUrl","https://relay.example/v1"));
        Check(CodexSessionModelSelection.Reconcile(relaySelection,relayConfig).S("modelProvider") == "relay", "valid explicit third-party session does not follow an unrelated global default");
        relaySelection["providerBaseUrl"] = "https://old-relay.example/v1";
        Check(!CodexSessionModelSelection.Reconcile(relaySelection,relayConfig).ContainsKey("providerBaseUrl"), "edited provider endpoint wins over discovery cache");
        var officialSelection = Obj(("model","gpt-native"),("modelProvider","openai"),("providerBaseUrl","http://localhost:15721/v1"));
        Check(!CodexSessionModelSelection.Reconcile(officialSelection,routing).ContainsKey("providerBaseUrl"), "official selections never carry a cached endpoint override");
        var missing = Obj(("model","removed-model"),("modelProvider","deleted"));
        Check(CodexSessionModelSelection.Reconcile(missing,routing,native).S("modelProvider") == "openai", "removed cached providers recover to current user configuration");
        var mirrorConfig = Obj(("model_providers", Obj(("custom", mirror))));
        var mirrorModels = CodexProviderModels.Catalog("custom", mirrorConfig, new JsonObject(), native, null);
        Check(mirrorModels.B("official_provider") && mirrorModels.A("data")[0].S("model") == "gpt-native", "mirror uses complete native catalog");
        var merged = CodexProviderModels.MergeOfficialAliases(new JsonArray(
            Obj(("slug", "custom"), ("official_provider", true)), Obj(("slug", "openai"), ("official_provider", true)), Obj(("slug", "local"))), "custom");
        Check(merged.Count == 2 && merged.Last().S("slug") == "custom" && merged.Last().A("aliases").Count == 2, "one official card preserves active native provider identity");
        var currentAliases = CodexProviderModels.MergeOfficialAliases(new JsonArray(
            Obj(("slug", "custom"), ("id", "custom"), ("official_provider", true), ("is_current", true)),
            Obj(("slug", "openai"), ("id", "openai"), ("official_provider", true))), "custom");
        Check(currentAliases.Single().B("is_current") && currentAliases.Single().B("current") &&
            currentAliases.Single().S("slug") == currentAliases.Single().S("id"), "official alias merge preserves both current/id contracts");
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
        Check(CodexProviderModels.IsOfficial("team", Obj(("name", "OpenAI (Team)"), ("requires_openai_auth", true))), "renaming a native official alias preserves routing");
        Check(!CodexProviderModels.IsOfficial("team", Obj(("name", "OpenAI (Team)"), ("requires_openai_auth", true), ("base_url", "https://relay.example/v1"))), "renamed authenticated relay remains third-party");
        try {
            CodexModelSettings.Validate(Obj(("provider", "local"), ("model", "gpt-native")), catalog, native.A("data"));
            throw new Exception("unrelated runtime model bypassed provider validation");
        } catch (CodexError) { }
        var aggregateNative = new JsonArray(Obj(("model", "ccs-relay/deepseek")));
        Check(CodexModelSettings.Validate(Obj(("provider", "local"), ("model", "ccs-relay/deepseek")), catalog, aggregateNative).B("aggregateModel"), "encoded aggregate model retains runtime fallback");
        var scoped = CodexProviderModels.Catalog("local", config, saved, native, discovered,
            Obj(("modelProvider", "local"), ("model", "session-only")));
        Check(scoped.A("data").Any(m => m.S("model") == "session-only"), "selected session model supplements its provider");
        Check(!catalog.A("data").Any(m => m.S("model") == "session-only"), "session supplement never mutates shared discovery");
        Check(!CodexProviderModels.Catalog("local", config, saved, native, discovered,
            Obj(("modelProvider", "other"), ("model", "foreign-session"))).A("data").Any(m => m.S("model") == "foreign-session"), "session model cannot cross provider boundaries");
        var externalSelection = CodexProviderModels.NativeSessionSelection(Obj(("id", "external-session"), ("modelProvider", "local")), Obj(("model", "desktop-only")));
        Check(CodexProviderModels.Catalog("local", config, saved, native, discovered, externalSelection).A("data").Any(m => m.S("model") == "desktop-only"), "native session metadata supplements a model without a RemoteTool override");
        Check(CodexProviderModels.NativeSessionSelection(Obj(("modelProvider", "local")), new()) == null, "missing native session model is never guessed from global defaults");
        Check(CodexProviderModels.NativeSessionSelection(new JsonObject(), Obj(("model", "desktop-only"))) == null, "native model without provider evidence cannot be assigned to a provider");
        var configuredSession = Obj(("model_provider", "local"), ("model", "config-only"));
        Check(CodexProviderModels.Catalog("local", configuredSession, new JsonObject(), native, null).A("data").Any(m => m.S("model") == "config-only"), "external global config model is already included without a RemoteTool override");
        Check(CodexProviderModels.AuthState(true, null, null, new()).S("auth_kind") == "missing", "catalog availability does not imply official credentials");
        var credentialState = CodexProviderModels.AuthState(false, endpoint, saved, new());
        Check(credentialState.S("auth_kind") == "api_key" && !credentialState.B("auth_verified") && !credentialState.ToJsonString().Contains("test-only-key"), "credential metadata never claims verified authentication or leaks keys");
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
        var providerFolder = Path.Combine(Path.GetTempPath(), "provider-catalog-" + Guid.NewGuid().ToString("N"));
        try {
            var path = CodexRuntimeCatalog.WriteProvider(providerFolder, "local", metadataCatalog, "glm");
            var generated = Read(path).A("models");
            Check(generated.Count == 3 && !generated.Any(row => row.S("slug") == "official"), "desktop provider directory contains only endpoint models");
            Check(generated.Single(row => row.S("slug") == "glm").L("context_window") == 1_000_000, "desktop catalog preserves reported capacity");
            var manual = Read(CodexRuntimeCatalog.WriteProvider(providerFolder, "manual", new JsonArray(), "manual-model")).A("models");
            Check(manual.Count == 1 && manual[0].S("slug") == "manual-model", "manual model is visible without leaking official cached models");
        } finally { if (Directory.Exists(providerFolder)) Directory.Delete(providerFolder, true); }
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
        var cleanupRoot = Path.Combine(Path.GetTempPath(), "codex-probe-cleanup-" + Guid.NewGuid().ToString("N"));
        var probeRoot = Path.Combine(cleanupRoot, "official-catalog");
        var staleProbe = Path.Combine(probeRoot, Guid.NewGuid().ToString("N"));
        var recentProbe = Path.Combine(probeRoot, Guid.NewGuid().ToString("N"));
        var unrelated = Path.Combine(probeRoot, "keep-this-folder");
        try {
            foreach (var path in new[] { staleProbe, recentProbe, unrelated }) {
                Directory.CreateDirectory(path); File.WriteAllText(Path.Combine(path, "fixture.txt"), "fixture");
            }
            Directory.SetLastWriteTimeUtc(staleProbe, DateTime.UtcNow.AddHours(-2));
            Directory.SetLastWriteTimeUtc(unrelated, DateTime.UtcNow.AddHours(-2));
            CodexCatalogMaintenance.Cleanup(cleanupRoot, DateTime.UtcNow);
            Check(!Directory.Exists(staleProbe), "expired official probe directory is removed");
            Check(Directory.Exists(recentProbe) && Directory.Exists(unrelated), "cleanup preserves recent probes and unrelated directories");
        } finally { if (Directory.Exists(cleanupRoot)) Directory.Delete(cleanupRoot, true); }
        Console.WriteLine("Codex provider model checks passed: scoped discovery, authentication, fallback, reasoning, resume and turn");
    }
    private sealed class ProbeHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => Task.FromResult(respond(request));
    }
}
