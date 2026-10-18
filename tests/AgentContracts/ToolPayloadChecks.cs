using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services;

static class ToolPayloadChecks
{
    public static void Run()
    {
        // Models can produce duplicate keys. JsonNode accepts the text initially,
        // then throws only when the object is first accessed.
        var arguments = """{"operations":[],"operations":[{"path":"a"}],"command":"first","command":"last"}""";
        if (ToolHistoryCache.Description(JsonValue.Create(arguments)) != "last")
            throw new Exception("Duplicate tool argument keys must use the last value");
        var calls = new JsonArray(new JsonObject {
            ["id"] = "call", ["function"] = new JsonObject {
                ["name"] = "terminal", ["arguments"] = arguments
            }
        });
        var rows = new JsonArray(
            new JsonObject { ["id"] = 1, ["role"] = "user", ["content"] = "test" },
            new JsonObject { ["id"] = 2, ["role"] = "assistant", ["tool_calls"] = calls },
            new JsonObject { ["id"] = 3, ["role"] = "tool", ["tool_call_id"] = "call",
                ["content"] = """{"operations":[],"operations":[],"exit_code":0,"exit_code":1}""" },
            new JsonObject { ["id"] = 4, ["role"] = "assistant", ["content"] = "reply" });
        var tools = ToolHistoryCache.HermesTools(rows, false);
        if (tools.Count != 1 || tools[0]!["status"]!.ToString() != "failed")
            throw new Exception("Duplicate output fields must not break tool results");
        var history = CompletedToolHistory.Reduce(new JsonObject { ["data"] = rows }, false);
        if (!history!["data"]!.AsArray().Any(row => row?["content"]?.ToString() == "reply"))
            throw new Exception("A tool payload must not prevent chat history synchronization");
        Console.WriteLine("Duplicate tool payload checks passed");
    }
}
