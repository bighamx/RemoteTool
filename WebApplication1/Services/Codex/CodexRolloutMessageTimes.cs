using System.Text;
using System.Text.Json;
using System.Security.Cryptography;

namespace ChuckieHelper.WebApi.Services.Codex;

/** Message identity, not receipt time or turn start, determines chronology after desktop continuation. */
internal static class CodexRolloutMessageTimes
{
    internal sealed record Entry(long Position,long Timestamp);
    internal sealed class Snapshot {
        private readonly Dictionary<string,Entry> ids;
        private readonly Dictionary<string,Entry[]> matches;
        public Snapshot(Dictionary<string,Entry> ids=null,Dictionary<string,Entry[]> matches=null) {
            this.ids=ids??new();this.matches=matches??new();
        }
        public bool Resolve(string id,string role,string text,ref long position,out long timestamp) {
            Entry found=null;
            var start=position;
            if(!string.IsNullOrWhiteSpace(id))ids.TryGetValue(id,out found);
            if(found==null && matches.TryGetValue(Signature(role,text),out var candidates))found=candidates.FirstOrDefault(item=>item.Position>=start);
            timestamp=found?.Timestamp??0;
            if(found==null)return false;
            position=Math.Max(position,found.Position+1);return true;
        }
    }
    private static string Signature(string role,string text) => role+":"+Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text.Replace("\r\n","\n").Trim())));
    private sealed class State {
        public long Offset, Modified;
        public MemoryStream Partial = new();
        public bool Oversized;
        public long Position;
        public Dictionary<string,Entry> Ids = new();
        public Dictionary<string,List<Entry>> Matches = new();
    }
    private static readonly object gate = new();
    private static readonly Dictionary<string,State> cache = new(StringComparer.OrdinalIgnoreCase);
    public static Snapshot Read(string home,string path) {
        try {
            if(string.IsNullOrWhiteSpace(path) || !RemoteControl.FilePathPolicy.IsWithin(home,path,false))return new();
            lock(gate) {
                var file=new FileInfo(path);if(!file.Exists)return new();
                if(!cache.TryGetValue(file.FullName,out var state) || file.Length<state.Offset || file.Length==state.Offset && file.LastWriteTimeUtc.Ticks!=state.Modified) {
                    state?.Partial.Dispose();
                    if(cache.Count>=16) { foreach(var old in cache.Values)old.Partial.Dispose();cache.Clear(); }
                    state=new State();cache[file.FullName]=state;
                }
                if(state.Offset<file.Length) {
                    using var stream=new FileStream(file.FullName,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete);
                    stream.Seek(state.Offset,SeekOrigin.Begin);var buffer=new byte[65536];
                    while(state.Offset<file.Length) {
                        var count=stream.Read(buffer,0,(int)Math.Min(buffer.Length,file.Length-state.Offset));if(count==0)break;
                        state.Offset+=count;
                        for(var i=0;i<count;i++) {
                            if(buffer[i]==(byte)'\n') {
                                if(!state.Oversized && state.Partial.Length>0)Apply(state,Encoding.UTF8.GetString(state.Partial.GetBuffer(),0,(int)state.Partial.Length));
                                state.Partial.SetLength(0);state.Oversized=false;
                                if(state.Partial.Capacity>256*1024) {state.Partial.Dispose();state.Partial=new MemoryStream();}
                            } else if(!state.Oversized) {
                                if(state.Partial.Length>=8*1024*1024) {state.Partial.SetLength(0);state.Oversized=true;}
                                else state.Partial.WriteByte(buffer[i]);
                            }
                        }
                    }
                }
                state.Modified=file.LastWriteTimeUtc.Ticks;
                return new Snapshot(new(state.Ids),state.Matches.ToDictionary(pair=>pair.Key,pair=>pair.Value.ToArray()));
            }
        } catch(Exception error) when(error is IOException or UnauthorizedAccessException or ArgumentException) { return new(); }
    }
    private static void Apply(State state,string line) {
        if(!line.Contains("response_item") || !line.Contains("message"))return;
        try {
            using var document=JsonDocument.Parse(line);var record=document.RootElement;
            if(!record.TryGetProperty("type",out var type) || type.GetString()!="response_item" || !record.TryGetProperty("payload",out var payload) ||
                !payload.TryGetProperty("type",out var itemType) || itemType.GetString()!="message" || !payload.TryGetProperty("role",out var roleNode))return;
            var role=roleNode.GetString();if(role is not ("user" or "assistant"))return;
            var id=payload.TryGetProperty("id",out var item)?item.GetString():null;
            if(id!=null && state.Ids.ContainsKey(id))return;
            if(record.TryGetProperty("timestamp",out var timestamp) && DateTimeOffset.TryParse(timestamp.GetString(),out var time)) {
                var text=payload.TryGetProperty("content",out var content) && content.ValueKind==JsonValueKind.Array
                    ? string.Join('\n',content.EnumerateArray().Where(part=>part.TryGetProperty("type",out var type) && type.GetString() is "input_text" or "output_text" or "text")
                        .Select(part=>part.TryGetProperty("text",out var value)?value.GetString():"")) : "";
                var entry=new Entry(state.Position++,time.ToUnixTimeMilliseconds());
                if(!string.IsNullOrWhiteSpace(id))state.Ids[id]=entry;
                var key=Signature(role,text);
                if(!state.Matches.TryGetValue(key,out var entries))state.Matches[key]=entries=new();entries.Add(entry);
                if(state.Position>10_000 && state.Position%1000==0) {
                    var cutoff=state.Position-10_000;
                    foreach(var old in state.Ids.Where(pair=>pair.Value.Position<cutoff).Select(pair=>pair.Key).ToArray())state.Ids.Remove(old);
                    foreach(var pair in state.Matches.ToArray()) {pair.Value.RemoveAll(value=>value.Position<cutoff);if(pair.Value.Count==0)state.Matches.Remove(pair.Key);}
                }
            }
        } catch(Exception error) when(error is JsonException or InvalidOperationException) { }
    }
}
