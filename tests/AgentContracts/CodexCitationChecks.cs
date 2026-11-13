using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexCitationChecks
{
    public static void Run() {
        const string marker = "\uE200cite\uE202turn792085news25\uE202turn784471view0\uE201";
        var text = "🙂 中文 " + marker;
        JsonObject Annotation(int start, int end) => new() { ["type"] = "url_citation", ["url"] = "https://example.com/report", ["title"] = "A source", ["start_index"] = start, ["end_index"] = end };
        var item = new JsonObject { ["type"] = "agentMessage", ["id"] = "message", ["text"] = text, ["annotations"] = new JsonArray(Annotation(6, 6 + marker.Length)) };
        var citations = CodexCitations.Read(item);
        if (citations.Count != 1 || citations[0]!["marker"]!.ToString() != marker || item["text"]!.ToString() != text) throw new Exception("UTF-16 source range or original text was changed");
        item["annotations"] = new JsonArray(Annotation(5, 5 + marker.Length));
        if (CodexCitations.Read(item)[0]!["marker"]!.ToString() != marker) throw new Exception("Unicode scalar citation range was not resolved");
        item["annotations"] = new JsonArray(new JsonObject { ["type"] = "url_citation", ["url_citation"] = new JsonObject { ["url"] = "https://example.com/other", ["reference_id"] = "turn792085news25" } }, new JsonObject { ["url"] = "javascript:alert(1)" }, new JsonObject { ["url"] = "https://user:password@example.com" }, "malformed");
        citations = CodexCitations.Read(item);
        if (citations.Count != 1 || citations[0]!["reference_id"]!.ToString() != "turn792085news25") throw new Exception("Citation source normalization did not reject unsafe or malformed entries");
        var stream = new CodexAssistantMessageStream();
        var turn = new JsonObject { ["status"] = "completed", ["items"] = new JsonArray(item.DeepClone()) };
        if (stream.Update(turn).Last()["citations"]?.AsArray().Count != 1) throw new Exception("Completed stream lost citation sources");
        turn["items"]![0]!["annotations"] = new JsonArray(Annotation(6, 6 + marker.Length));
        if (stream.Update(turn).Single()["event"]!.ToString() != "message.completed") throw new Exception("A source-only update was not emitted");
        if (stream.Update(turn).Any()) throw new Exception("Repeated metadata emitted duplicate completion");
        Console.WriteLine("PASS: Codex citations, safe source URLs, Unicode offsets, original text and streaming metadata updates");
    }
}
