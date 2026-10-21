using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>Expand Hermes' raw-record pages until the requested visible-message window is covered.</summary>
public static class HermesHistoryPages
{
    public static async Task<JsonNode> ReadAsync(
        Func<int, int, CancellationToken, Task<JsonNode>> fetch,
        int limit, string fromId, string olderBefore, CancellationToken ct)
    {
        const int pageSize = 500;
        var offset = 0;
        JsonNode document = null;
        var records = new SortedDictionary<long, JsonNode>();
        var cursor = olderBefore ?? fromId;
        var visibleNeeded = string.IsNullOrEmpty(cursor) || olderBefore != null ? Math.Clamp(limit, 1, 500) + 1 : 1;
        while (true) {
            ct.ThrowIfCancellationRequested();
            var page = await fetch(pageSize, offset, ct);
            if (page?["data"] is not JsonArray rows) throw new InvalidOperationException("Hermes 返回了无效的历史数据");
            document ??= page;
            // Older gateways returned the complete transcript without pagination metadata.
            if (page["pagination"] is not JsonObject) return page;
            foreach (var row in rows) {
                if (!long.TryParse(row?["id"]?.ToString(), out var id))
                    throw new InvalidOperationException("Hermes 返回了无效的历史消息标识");
                records.TryAdd(id, row);
            }
            if (rows.Count < pageSize) break;
            IEnumerable<KeyValuePair<long, JsonNode>> preceding = records;
            if (!string.IsNullOrEmpty(cursor)) {
                if (!long.TryParse(cursor, out var anchor) || !records.ContainsKey(anchor)) {
                    offset = checked(offset + rows.Count);
                    continue;
                }
                preceding = records.TakeWhile(pair => pair.Key < anchor);
            }
            // Read one extra visible message so has_more is determined from real history,
            // even if a raw page is entirely tools or cuts through the middle of a turn.
            if (preceding.Count(pair => AgentHistoryWindow.Visible(pair.Value)) >= visibleNeeded) break;
            offset = checked(offset + rows.Count);
        }
        document["data"] = new JsonArray(records.Values.Select(row => row.DeepClone()).ToArray());
        document.AsObject().Remove("pagination");
        return document;
    }
}
