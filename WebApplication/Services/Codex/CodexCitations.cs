using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexCitations
{
    private static readonly Regex Marker = new("^\uE200cite\uE202[^\uE200\uE201\\r\\n]+\uE201$", RegexOptions.CultureInvariant);
    public static JsonArray Read(JsonNode item) {
        var result = new JsonArray();
        var entries = item.A("citations").Concat(item.A("annotations"))
            .Concat(item.A("content").OfType<JsonObject>().SelectMany(part => part.A("annotations")));
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var entry in entries) {
            if (entry is not JsonObject parent) continue;
            var citation = parent["url_citation"] as JsonObject ?? parent;
            var raw = citation.S("url");
            if (raw.Any(char.IsControl) || !Uri.TryCreate(raw, UriKind.Absolute, out var url) || url.Scheme is not ("http" or "https") || url.UserInfo.Length > 0) continue;
            var reference = citation.S("reference_id", citation.S("ref_id"));
            var marker = citation.S("marker");
            var start = long.TryParse(citation.S("start_index"), out var first) ? first : -1;
            var end = long.TryParse(citation.S("end_index"), out var last) ? last : -1;
            if (!Marker.IsMatch(marker)) marker = At(item.S("text"), start, end);
            var key = reference + "\n" + marker + "\n" + url.AbsoluteUri;
            if (seen.Add(key)) result.Add(Obj(("reference_id", reference), ("marker", marker), ("url", url.AbsoluteUri), ("title", citation.S("title"))));
        }
        return result;
    }
    private static string At(string text, long start, long end) {
        if (start < 0 || end <= start || end > text.Length) return "";
        var value = text[(int)start..(int)end];
        if (Marker.IsMatch(value)) return value;
        // Some providers count Unicode scalars, while .NET indexes UTF-16 code units.
        var scalar = new StringBuilder(); var index = 0;
        foreach (var rune in text.EnumerateRunes()) {
            if (index >= end) break;
            if (index++ >= start) scalar.Append(rune.ToString());
        }
        value = scalar.ToString();
        return Marker.IsMatch(value) ? value : "";
    }
}
