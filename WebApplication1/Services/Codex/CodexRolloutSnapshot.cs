using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

/** Incremental read-only metadata; never reparse an entire growing transcript on every poll. */
internal static class CodexRolloutSnapshot
{
    private sealed class State {
        public long Offset, Modified;
        public MemoryStream Partial = new();
        public bool Oversized, Running;
        public JsonObject Settings = new(), Context = Obj(("available", false)), Question;
        public Dictionary<string, JsonObject> Pending = new();
        public long StartedAt, LastResponseAt, ActivityRevision;
        public string TurnId = "";
        public int ToolCount;
        public Dictionary<string, JsonObject> Tools = new();
    }
    private static readonly object gate = new();
    private static readonly Dictionary<string, State> cache = new(StringComparer.OrdinalIgnoreCase);
    public static JsonObject Read(string home, string path) {
        var empty = Obj(("settings", new JsonObject()), ("context", Obj(("available", false))), ("question", null), ("running", false));
        if (string.IsNullOrWhiteSpace(path)) return empty;
        try {
            if (!RemoteControl.FilePathPolicy.IsWithin(home, path, false)) return empty;
            lock (gate) {
                var file = new FileInfo(path); if (!file.Exists) return empty;
                if (!cache.TryGetValue(file.FullName, out var state) || file.Length < state.Offset ||
                    (file.Length == state.Offset && file.LastWriteTimeUtc.Ticks != state.Modified)) {
                    state?.Partial.Dispose();
                    if (cache.Count >= 256) { foreach (var old in cache.Values) old.Partial.Dispose(); cache.Clear(); }
                    state = new State(); cache[file.FullName] = state;
                }
                if (state.Offset < file.Length) {
                    using var stream = new FileStream(file.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    stream.Seek(state.Offset, SeekOrigin.Begin);
                    var buffer = new byte[65536];
                    while (state.Offset < file.Length) {
                        var count = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, file.Length - state.Offset));
                        if (count == 0) break;
                        state.Offset += count;
                        for (var i = 0; i < count; i++) {
                            var value = buffer[i];
                            if (value == (byte)'\n') {
                                if (!state.Oversized && state.Partial.Length > 0) Apply(state, Encoding.UTF8.GetString(state.Partial.GetBuffer(), 0, (int)state.Partial.Length));
                                state.Partial.SetLength(0); state.Oversized = false;
                                if (state.Partial.Capacity > 256 * 1024) { state.Partial.Dispose(); state.Partial = new MemoryStream(); }
                            } else if (!state.Oversized) {
                                if (state.Partial.Length >= 16 * 1024 * 1024) { state.Partial.SetLength(0); state.Oversized = true; }
                                else state.Partial.WriteByte(value);
                            }
                        }
                    }
                }
                state.Modified = file.LastWriteTimeUtc.Ticks;
                var context = state.Context.DeepClone().AsObject();
                if (context.L("tokens") > 0) context["available"] = true;
                return Obj(("settings", state.Settings), ("context", context), ("question", state.Question), ("running", state.Running),
                    ("activity", Obj(("started_at", state.StartedAt > 0 ? (object)state.StartedAt : null),
                        ("last_response_at", state.LastResponseAt > 0 ? (object)state.LastResponseAt : null), ("activity_id", state.TurnId),
                        ("revision", state.ActivityRevision), ("event_count", state.ToolCount),
                        ("progress", new JsonArray(state.Tools.Values.TakeLast(30).Select(tool => tool.DeepClone()).ToArray())))));
            }
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException) { return empty; }
    }
    private static void Apply(State state, string line) {
        try {
            if (JsonNode.Parse(line) is not JsonObject record || record["payload"] is not JsonObject payload) return;
            var kind = record.S("type");
            var timestamp = DateTimeOffset.TryParse(record.S("timestamp"), out var time) ? time.ToUnixTimeMilliseconds() : 0;
            if (kind == "turn_context") {
                state.Settings = Obj(("model", payload["model"]), ("reasoningEffort", payload["effort"] ?? payload["reasoning_effort"]),
                    ("serviceTier", payload["service_tier"]), ("collaborationMode", payload["collaboration_mode"]));
            }
            if (kind == "compacted") state.Context = Obj(("available", false));
            if (kind == "token_usage_record" && payload["usage"] is { } usage) state.Context["tokens"] = usage.L("total_tokens");
            if (kind == "event_msg") {
                var type = payload.S("type");
                if (type == "task_started") {
                    state.Running = true; state.StartedAt = timestamp; state.LastResponseAt = 0;
                    state.TurnId = payload.S("turn_id"); state.Tools.Clear(); state.ToolCount = 0; state.ActivityRevision++;
                } else if (type is "task_complete" or "turn_aborted" &&
                    (state.TurnId.Length == 0 || payload.S("turn_id").Length == 0 || payload.S("turn_id") == state.TurnId)) {
                    state.Running = false; state.ActivityRevision++;
                }
                if (type == "token_count" && payload["info"] is { } info) {
                    var last = info["last_token_usage"]; var limit = info.L("model_context_window");
                    state.Context = Obj(("available", last != null), ("tokens", last.L("total_tokens")), ("limit", limit > 0 ? (object)limit : null), ("estimated", false));
                }
            }
            if (kind != "response_item") return;
            if (state.Running) {
                var itemType = payload.S("type");
                if (itemType is "function_call" or "custom_tool_call") {
                    var id = payload.S("call_id");
                    if (id.Length > 0 && !state.Tools.ContainsKey(id)) {
                        var name = payload.S("name").Split('.').Last();
                        var preview = payload.S(itemType == "custom_tool_call" ? "input" : "arguments");
                        try {
                            if (preview.Length <= 65536 && JsonNode.Parse(preview) is JsonObject arguments)
                                preview = arguments.S("cmd", arguments.S("command", arguments.S("code", arguments.S("path", preview))));
                        } catch (JsonException) { }
                        if (preview.Length > 2048) preview = preview[..2048];
                        preview = System.Text.RegularExpressions.Regex.Replace(preview, @"\s+", " ").Trim();
                        if (preview.Length > 160) preview = preview[..160] + "…";
                        state.Tools[id] = Obj(("id", id), ("tool", name), ("preview", preview), ("status", "running"), ("timestamp", timestamp));
                        state.ToolCount++; state.LastResponseAt = Math.Max(state.LastResponseAt, timestamp); state.ActivityRevision++;
                        if (state.Tools.Count > 128) state.Tools.Remove(state.Tools.Keys.First());
                    }
                } else if (itemType is "function_call_output" or "custom_tool_call_output" && state.Tools.TryGetValue(payload.S("call_id"), out var tool)) {
                    tool["status"] = "completed"; tool["timestamp"] = timestamp;
                    try {
                        var output = payload.S("output");
                        if (output.Length <= 65536 && JsonNode.Parse(output) is JsonObject result && result.B("isError")) tool["status"] = "failed";
                    } catch (JsonException) { }
                    state.LastResponseAt = Math.Max(state.LastResponseAt, timestamp); state.ActivityRevision++;
                } else if (itemType == "reasoning" || itemType == "message" && payload.S("role") == "assistant") {
                    state.LastResponseAt = Math.Max(state.LastResponseAt, timestamp); state.ActivityRevision++;
                }
            }
            if (payload.S("type") == "message" && payload.S("role") == "user") { state.Question = null; state.Pending.Clear(); }
            if (payload.S("type") == "function_call" && payload.S("name").Split('.').Last() == "request_user_input_async") {
                var arguments = JsonNode.Parse(payload.S("arguments", "{}"));
                var questions = CodexSessionDetails.Questions(arguments?["questions"]);
                var id = payload.S("call_id", Hash(line)[..24]);
                if (questions.Count > 0) {
                    if (state.Pending.Count >= 32) state.Pending.Remove(state.Pending.Keys.First());
                    state.Pending[id] = Obj(("request_id", id), ("questions", questions), ("async", true));
                }
            }
            if (payload.S("type") == "function_call_output" && state.Pending.Remove(payload.S("call_id"), out var candidate)) {
                if (JsonNode.Parse(payload.S("output", "{}" )).B("accepted")) state.Question = candidate;
            }
        } catch (Exception error) when (error is JsonException or InvalidOperationException) { }
    }
}
