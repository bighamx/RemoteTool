using System.Text.Json;
using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexRuntimeCatalog
{
    public static long Window(JsonNode model) => model.L("context_window") is > 0 and <= 1_000_000_000 ? model.L("context_window") : 0;

    public static JsonObject Build(JsonObject existing, string model, JsonObject metadata) {
        var window = Window(metadata);
        if (window == 0) throw new CodexError("供应商未提供有效上下文窗口");
        var entry = Obj(("slug", model), ("display_name", model), ("description", model),
            ("base_instructions", "You are Codex, a coding agent. Collaborate with the user in the shared workspace to complete their tasks."),
            ("default_reasoning_level", metadata.S("defaultReasoningEffort", "medium")),
            ("supported_reasoning_levels", new JsonArray(metadata.A("supportedReasoningEfforts")
                .Select(e => (JsonNode)Obj(("effort", e.S("reasoningEffort")), ("description", e.S("reasoningEffort")))).ToArray())),
            ("shell_type", "unified_exec"), ("visibility", "list"), ("supported_in_api", true), ("priority", 0),
            ("supports_reasoning_summaries", !metadata.ContainsKey("supports_reasoning") || metadata.B("supports_reasoning")),
            ("default_reasoning_summary", "auto"), ("support_verbosity", false),
            ("truncation_policy", Obj(("mode", "bytes"), ("limit", 10000))),
            ("context_window", window), ("max_context_window", window), ("effective_context_window_percent", 95),
            ("experimental_supported_tools", new JsonArray()),
            ("input_modalities", metadata.ContainsKey("supports_images") && !metadata.B("supports_images") ? new JsonArray("text") : new JsonArray("text", "image")));
        var models = new JsonArray(existing.A("models").Where(m => m.S("slug") != model).Select(m => m.DeepClone()).ToArray());
        models.Add(entry);
        return Obj(("models", models));
    }

    public static string Write(string home, string folder, string provider, string model, JsonObject metadata, string configuredCatalog = "") {
        if (Window(metadata) == 0) return null; // Unknown capacity must retain Codex's conservative fallback.
        var existing = new JsonObject();
        foreach (var path in new[] { configuredCatalog, Path.Combine(home, "models_cache.json") }.Where(p => !string.IsNullOrWhiteSpace(p))) {
            try { existing = Read(path); if (existing.A("models").Count > 0) break; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { }
        }
        var catalog = Build(existing, model, metadata);
        // Immutable files keep already-running clients on their original catalog generation.
        var pathName = Path.Combine(folder, "runtime-model-catalogs", Hash(provider + "\n" + catalog.ToJsonString()) + ".json");
        if (!File.Exists(pathName)) Atomic(pathName, catalog);
        return pathName;
    }

    public static void ApplyContext(JsonObject config, JsonObject metadata) {
        var window = Window(metadata);
        if (window == 0) return;
        config["model_context_window"] = window;
        config["model_auto_compact_token_limit"] = window * 90 / 100;
    }
}
