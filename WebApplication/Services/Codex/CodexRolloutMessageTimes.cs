using System.Text;
using System.Text.Json;
using System.Security.Cryptography;

namespace RemoteTool.WebApi.Services.Codex;

/** Message identity, not receipt time or turn start, determines chronology after desktop continuation. */
internal static class CodexRolloutMessageTimes
{
    internal sealed record Entry(long Position,long Timestamp);
    internal sealed class Snapshot {
        private readonly Dictionary<string,Entry> ids;
        private readonly Dictionary<string,Entry[]> matches;
        private readonly bool strict;
        public Snapshot(Dictionary<string,Entry> ids=null,Dictionary<string,Entry[]> matches=null,bool strict=false) {
            this.strict=strict;
            this.ids=ids??new();this.matches=matches??new();
        }
        public bool Resolve(string id,string role,string text,ref long position,out long timestamp) {
            Entry found=null;
            var start=position;
            if(!string.IsNullOrWhiteSpace(id))ids.TryGetValue(id,out found);
            if(found==null && matches.TryGetValue(Signature(role,text),out var candidates)) {
                var eligible=candidates.Where(item=>item.Position>=start).Take(strict?2:1).ToArray();
                if(eligible.Length==1)found=eligible[0];
            }
            timestamp=found?.Timestamp??0;
            if(found==null)return false;
            position=Math.Max(position,found.Position+1);return true;
        }
        public static Snapshot Merge(IEnumerable<Snapshot> sources) {
            var snapshots=sources.ToArray();
            var positions=snapshots.SelectMany(s=>s.matches.Values.SelectMany(v=>v).Concat(s.ids.Values))
                .Select(e=>e.Timestamp).Distinct().Order().Select((time,index)=>(time,index))
                .ToDictionary(p=>p.time,p=>(long)p.index);
            Entry Global(Entry entry)=>new(positions[entry.Timestamp],entry.Timestamp);
            var mergedIds=snapshots.SelectMany(s=>s.ids).GroupBy(p=>p.Key)
                .ToDictionary(g=>g.Key,g=>Global(g.MinBy(p=>p.Value.Timestamp).Value));
            var mergedMatches=snapshots.SelectMany(s=>s.matches).GroupBy(p=>p.Key)
                .ToDictionary(g=>g.Key,g=>g.SelectMany(p=>p.Value).DistinctBy(e=>e.Timestamp)
                    .OrderBy(e=>e.Timestamp).Select(Global).ToArray());
            return new(mergedIds,mergedMatches,true);
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
    private static readonly Dictionary<string,(DateTime Checked,string[] Paths)> histories = new(StringComparer.OrdinalIgnoreCase);
    public static Snapshot ReadSession(string home,string session,string original) {
        if(!System.Text.RegularExpressions.Regex.IsMatch(session??"", "^[a-zA-Z0-9_-]{1,160}$"))return new();
        try {
            var root=Path.Combine(home,"sessions");
            var key=Path.GetFullPath(home)+"|"+session;
            string[] paths;
            lock(gate) {
                if(histories.TryGetValue(key,out var known) && DateTime.UtcNow-known.Checked<TimeSpan.FromSeconds(10))paths=known.Paths;
                else {
                    var options=new EnumerationOptions {RecurseSubdirectories=true,AttributesToSkip=FileAttributes.ReparsePoint,IgnoreInaccessible=true};
                    paths=Directory.Exists(root)?Directory.EnumerateFiles(root,"rollout-*-"+session+".jsonl",options)
                        .Concat(Directory.EnumerateFiles(root,"rollout-*-"+session+"_*.jsonl",options)).ToArray():Array.Empty<string>();
                    if(histories.Count>=512)histories.Clear();
                    histories[key]=(DateTime.UtcNow,paths);
                }
            }
            return Snapshot.Merge(paths.Concat(string.IsNullOrWhiteSpace(original)?Array.Empty<string>():new[]{original})
                .Distinct(StringComparer.OrdinalIgnoreCase).Select(path=>Read(home,path)));
        } catch(Exception error) when(error is IOException or UnauthorizedAccessException or ArgumentException) {return Read(home,original);}
    }
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
                var hasImages=payload.TryGetProperty("content",out var imageContent) && imageContent.ValueKind==JsonValueKind.Array &&
                    imageContent.EnumerateArray().Any(part=>part.TryGetProperty("type",out var imageType) && imageType.GetString()=="input_image");
                var text=payload.TryGetProperty("content",out var content) && content.ValueKind==JsonValueKind.Array
                    ? string.Join('\n',content.EnumerateArray().Where(part=>part.TryGetProperty("type",out var type) && type.GetString() is "input_text" or "output_text" or "text")
                        .Where(part=>role!="user" || !hasImages || !ImageFrame(part))
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
    private static bool ImageFrame(JsonElement part) {
        if(!part.TryGetProperty("text",out var value) || value.ValueKind!=JsonValueKind.String)return false;
        var text=value.GetString().Trim();
        return text=="</image>" || text.StartsWith("<image name=[Image #",StringComparison.Ordinal) &&
            text.EndsWith(">",StringComparison.Ordinal) && !text.Contains('\n') && !text.Contains('\r');
    }
}
