using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexEventReplayGap {
    public static JsonObject Marker(long last, long first) => first > last && first - last > 1
        ? Obj(("event", "stream.gap"), ("seq", first - 1), ("from_seq", last + 1), ("to_seq", first - 1))
        : null;
}
