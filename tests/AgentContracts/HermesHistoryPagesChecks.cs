using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services;

internal static class HermesHistoryPagesChecks
{
    public static async Task Run() {
        static void Check(bool ok,string label){if(!ok)throw new Exception(label);}
        var source = Enumerable.Range(1,1200).Select(id => (JsonNode)new JsonObject {
            ["id"]=id,["role"]=id>700?"tool":"assistant",["content"]="row "+id
        }).ToArray();
        var offsets = new List<int>();
        Task<JsonNode> Fetch(int limit,int offset,CancellationToken ct) {
            ct.ThrowIfCancellationRequested(); offsets.Add(offset);
            return Task.FromResult<JsonNode>(new JsonObject {
                ["data"]=new JsonArray(source.Reverse().Skip(offset).Take(limit).Select(r=>r.DeepClone()).ToArray()),
                ["pagination"]=new JsonObject{["offset"]=offset}
            });
        }
        var latest = AgentHistoryWindow.Select(await HermesHistoryPages.ReadAsync(Fetch,100,null,null,default),100);
        Check(offsets.SequenceEqual(new[]{0,500}),"history continues past an entire tools-only page");
        Check(latest["data"]!.AsArray().Count(AgentHistoryWindow.Visible)==100 && latest["has_more"]!.GetValue<bool>(),"latest window counts visible messages and reports actual older messages");
        Check(latest["oldest_id"]!.GetValue<int>()==601,"latest visible window keeps the correct ordered cursor");
        offsets.Clear();
        var older=AgentHistoryWindow.Select(await HermesHistoryPages.ReadAsync(Fetch,100,null,"601",default),100,null,"601");
        Check(older["oldest_id"]!.GetValue<int>()==501 && older["data"]!.AsArray()[0]!["id"]!.GetValue<int>()==501,"older cursor expands loaded history without losing recent records");
        var tail=AgentHistoryWindow.Select(await HermesHistoryPages.ReadAsync(Fetch,100,"650",null,default),100,"650");
        Check(tail["data"]!.AsArray()[0]!["id"]!.GetValue<int>()==650,"incremental refresh retains its stable anchor");
        var legacy=new JsonObject{["data"]=new JsonArray(new JsonObject{["id"]=1,["role"]="user",["content"]="legacy"})};
        Check(ReferenceEquals(legacy,await HermesHistoryPages.ReadAsync((_,_,_)=>Task.FromResult<JsonNode>(legacy),100,null,null,default)),"legacy full-history response remains supported");
        using var cancelled=new CancellationTokenSource();cancelled.Cancel();
        try {await HermesHistoryPages.ReadAsync(Fetch,100,null,null,cancelled.Token);throw new Exception("cancelled read continued");}
        catch(OperationCanceledException){}
        Console.WriteLine("Hermes history paging checks passed: tools-only page, visible window, older/incremental cursors, legacy and cancellation");
    }
}
