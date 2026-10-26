using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SQLite;

namespace RemoteTool.WebApi.Services;

/// <summary>Attachment membership comes exclusively from Hermes' canonical messages.</summary>
public sealed class HermesNativeAttachments(HermesProfiles profiles, HermesAttachments uploads)
{
    private const int MaxInlineBytes = 20 * 1024 * 1024;
    private sealed class Message {
        [Column("id")] public long Id { get; set; }
        [Column("role")] public string Role { get; set; }
        [Column("content")] public string Content { get; set; }
    }
    public sealed record Content(string Path, byte[] Bytes, string Mime, string Name);
    private sealed record Source(string Path, string Data, string Mime, string Name) {
        public string Digest => Hash(Data ?? (System.IO.Path.GetFullPath(Path) + "|" + File.GetLastWriteTimeUtc(Path).Ticks + "|" + new FileInfo(Path).Length));
        public long Size => Data == null ? new FileInfo(Path).Length : (long)(Data.Length-Data.IndexOf(',')-1) * 3 / 4 - (Data.EndsWith("==") ? 2 : Data.EndsWith('=') ? 1 : 0);
    }
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant()[..32];
    private static string FileMime(string path) {
        var mime=HermesAttachments.Mime(path);
        if(mime!="application/octet-stream") return mime;
        using var stream=File.OpenRead(path);
        Span<byte> bytes=stackalloc byte[12]; var size=stream.Read(bytes);
        if(size>=8 && bytes[..8].SequenceEqual(new byte[]{137,80,78,71,13,10,26,10})) return "image/png";
        if(size>=3 && bytes[0]==255 && bytes[1]==216 && bytes[2]==255) return "image/jpeg";
        if(size>=6 && Encoding.ASCII.GetString(bytes[..6]) is "GIF87a" or "GIF89a") return "image/gif";
        if(size==12 && Encoding.ASCII.GetString(bytes[..4])=="RIFF" && Encoding.ASCII.GetString(bytes[8..])=="WEBP") return "image/webp";
        return mime;
    }
    private IEnumerable<Message> Messages(string session, long? message = null) {
        if (!Regex.IsMatch(session,"^[a-zA-Z0-9_-]{1,160}$")) throw new ArgumentException("无效会话标识");
        var path = System.IO.Path.Combine(profiles.Home,"state.db");
        if (!File.Exists(path)) yield break;
        SQLitePCL.Batteries_V2.Init();
        using var db = new SQLiteConnection(path,SQLiteOpenFlags.ReadOnly);
        var sql="SELECT id,role,content FROM messages WHERE session_id=?"+(message.HasValue?" AND id=?":"")+" ORDER BY id";
        var args=message.HasValue?new object[]{session,message.Value}:new object[]{session};
        foreach(var row in db.DeferredQuery<Message>(sql,args)) yield return row;
    }
    private static JsonNode Decode(string value) {
        if (value?.StartsWith("\0json:",StringComparison.Ordinal)==true) {
            try { return JsonNode.Parse(value[6..]); } catch(JsonException) { }
        }
        return JsonValue.Create(value??"");
    }
    private static IEnumerable<string> Text(JsonNode node) {
        if(node is JsonValue text && text.TryGetValue<string>(out var s)) yield return s;
        else if(node is JsonArray list) foreach(var child in list) foreach(var s2 in Text(child)) yield return s2;
        else if(node is JsonObject obj && obj["text"] is JsonValue value && value.TryGetValue<string>(out var t)) yield return t;
    }
    private static IEnumerable<string> Images(JsonNode node) {
        if(node is JsonArray list) foreach(var child in list) foreach(var url in Images(child)) yield return url;
        else if(node is JsonObject obj && obj["type"]?.ToString() is "image_url" or "input_image") {
            var image=obj["image_url"];
            var url=image is JsonObject nested?nested["url"]?.ToString():image?.ToString()??obj["url"]?.ToString();
            if(url!=null) yield return url;
        }
    }
    internal static string ReferencePath(string line) {
        line=line.Trim();
        if(line.StartsWith("@image:",StringComparison.Ordinal)) {
            var value=line[7..].Trim();
            if(value.Length>1 && value[0]=='"' && value[^1]=='"') {
                try { return JsonSerializer.Deserialize<string>(value); } catch(JsonException) { return value[1..^1]; }
            }
            if(value.Length>1 && value[0] is '`' or '\'' && value[^1]==value[0]) return value[1..^1];
            return value;
        }
        if(line.StartsWith("[Image attached at: ",StringComparison.Ordinal) && line.EndsWith(']')) return line[20..^1];
        var match=Regex.Match(line,@"^\[[^\]\r\n]*\]\(<?(file:[^\r\n>]+?)>?\)$",RegexOptions.None,TimeSpan.FromMilliseconds(100));
        return match.Success && Uri.TryCreate(match.Groups[1].Value,UriKind.Absolute,out var uri) && uri.IsFile?uri.LocalPath:null;
    }
    private bool Safe(string session,string path,bool delivered) {
        if(!System.IO.Path.IsPathFullyQualified(path)) return false;
        var full=System.IO.Path.GetFullPath(path);
        var root=System.IO.Path.GetDirectoryName(uploads.Folder(session))!;
        var homes=new[]{root,System.IO.Path.Combine(profiles.Home,"cache","images"),System.IO.Path.Combine(profiles.Home,"image_cache"),
            System.IO.Path.Combine(profiles.Home,"cache","documents"),System.IO.Path.Combine(profiles.Home,"document_cache"),
            System.IO.Path.Combine(profiles.Home,"cache","audio"),System.IO.Path.Combine(profiles.Home,"cache","video")};
        var comparison=OperatingSystem.IsWindows()?StringComparison.OrdinalIgnoreCase:StringComparison.Ordinal;
        var allowed=homes.FirstOrDefault(home=>full.StartsWith(System.IO.Path.GetFullPath(home)+System.IO.Path.DirectorySeparatorChar,comparison));
        if(allowed==null && !delivered) return false;
        if(allowed==root && System.IO.Path.GetRelativePath(root,full).Split(System.IO.Path.DirectorySeparatorChar).Any(part=>part.StartsWith('.'))) return false;
        var info=new FileInfo(full);
        if(!info.Exists || info.Length>500L*1024*1024) return false;
        for(FileSystemInfo current=info;current!=null;current=current is FileInfo f?f.Directory:((DirectoryInfo)current).Parent) {
            if((current.Attributes&FileAttributes.ReparsePoint)!=0) return false;
            if(allowed!=null && current.FullName.Equals(System.IO.Path.GetFullPath(allowed),comparison)) break;
        }
        return true;
    }
    private IEnumerable<Source> Sources(string session,Message message) {
        if(message.Role is not ("user" or "assistant")) yield break;
        var node=Decode(message.Content);
        var inlineHashes=new HashSet<string>(StringComparer.Ordinal);
        if(message.Role=="user") foreach(var data in Images(node).Distinct().Take(8)) {
            var comma=data.IndexOf(',');
            if(comma<0 || comma>64 || data.Length-comma-1>(MaxInlineBytes+2L)/3*4) continue;
            var header=data[..comma].ToLowerInvariant();
            if(header is "data:image/png;base64" or "data:image/jpeg;base64" or "data:image/gif;base64" or "data:image/webp;base64") {
                string digest=null;
                try { digest=Convert.ToHexString(SHA256.HashData(Convert.FromBase64String(data[(comma+1)..]))); } catch(FormatException) { }
                if(digest==null || !inlineHashes.Add(digest)) continue;
                var mime=header[5..^7];
                yield return new Source(null,data,mime,"图片."+(mime=="image/jpeg"?"jpg":mime[6..]));
            }
        }
        var text=string.Join('\n',Text(node));
        var paths=message.Role=="assistant"?AssistantMediaPaths.Read(text):text.Split('\n').Select(ReferencePath).Where(path=>!string.IsNullOrWhiteSpace(path));
        foreach(var path in paths.Distinct(OperatingSystem.IsWindows()?StringComparer.OrdinalIgnoreCase:StringComparer.Ordinal).Take(32)) {
            Source source=null;
            try {
                if(Safe(session,path,message.Role=="assistant")) {
                    var mime=FileMime(path);
                    if(inlineHashes.Count>0 && mime.StartsWith("image/") && new FileInfo(path).Length<=MaxInlineBytes) {
                        using var image=File.OpenRead(path);
                        if(inlineHashes.Contains(Convert.ToHexString(SHA256.HashData(image)))) continue;
                    }
                    var name=System.IO.Path.GetFileName(path);
                    if(name.Length>33 && name[32]=='_' && Guid.TryParseExact(name[..32],"N",out _)) name=name[33..];
                    source=new Source(System.IO.Path.GetFullPath(path),null,mime,name);
                }
            } catch(Exception e) when(e is IOException or UnauthorizedAccessException or ArgumentException) { }
            if(source!=null) yield return source;
        }
    }
    private object Metadata(Message message,Source source,string session) {
        var digest=source.Digest;
        return new {id=$"native_{message.Id}_{digest}",name=source.Name,size=source.Size,mime=source.Mime,
            outgoing=message.Role=="assistant",url=$"{HermesProfileScope.ApiRoot}/sessions/{session}/files/native_{message.Id}_{digest}"};
    }
    private object TryMetadata(Message message,Source source,string session) {
        try { return Metadata(message,source,session); }
        catch(Exception error) when(error is IOException or UnauthorizedAccessException) { return null; }
    }
    public JsonNode Present(string session,string json) {
        var result=JsonNode.Parse(json)!;
        if(result["data"] is not JsonArray rows) return result;
        foreach(var row in rows.OfType<JsonObject>().Where(row=>row["role"]?.ToString() is "user" or "assistant")) {
            if(!long.TryParse(row["id"]?.ToString(),out var id)) continue;
            var message=Messages(session,id).FirstOrDefault();
            if(message==null) continue;
            var sources=Sources(session,message).ToArray();
            var cards=sources.Select(source=>TryMetadata(message,source,session)).Where(card=>card!=null).ToArray();
            row["attachments"]=JsonSerializer.SerializeToNode(cards);
            if(message.Role=="user" && cards.Length>0 && row["content"] is JsonValue value && value.TryGetValue<string>(out var text)) {
                row["content"]=string.Join('\n',text.Split('\n').Where(line=>ReferencePath(line)==null && line.Trim() is not ("[image]" or "[screenshot]"))).TrimEnd();
            }
        }
        return result;
    }
    public object[] List(string session) => Messages(session).SelectMany(message=>Sources(session,message).Select(source=>TryMetadata(message,source,session))).Where(card=>card!=null).ToArray();
    public Content Resolve(string session,string id) {
        if(!id.StartsWith("native_",StringComparison.Ordinal)) {
            var path=uploads.Resolve(session,id);
            return new Content(path,null,HermesAttachments.Mime(path),System.IO.Path.GetFileName(path));
        }
        var match=Regex.Match(id,@"^native_([1-9][0-9]*)_([0-9a-f]{32})$");
        if(!match.Success || !long.TryParse(match.Groups[1].Value,out var mid)) throw new FileNotFoundException("附件不存在");
        var message=Messages(session,mid).FirstOrDefault();
        var source=message==null?null:Sources(session,message).FirstOrDefault(source=>source.Digest==match.Groups[2].Value);
        if(source==null) throw new FileNotFoundException("Hermes 会话中的附件已不存在");
        if(source.Data==null) return new Content(source.Path,null,source.Mime,source.Name);
        try { return new Content(null,Convert.FromBase64String(source.Data[(source.Data.IndexOf(',')+1)..]),source.Mime,source.Name); }
        catch(FormatException) { throw new FileNotFoundException("Hermes 图片数据无效"); }
    }
    private JsonElement ResolveInput(JsonElement input) {
        var body=JsonNode.Parse(input.GetRawText())!.AsObject();
        var session=body["session_id"]!.ToString();
        if(body["attachment_ids"] is not JsonArray ids) return input;
        if(ids.Count>8) throw new ArgumentException("每条消息最多 8 个附件");
        var resolved=new JsonArray();
        foreach(var id in ids) {
            if(!id!.ToString().StartsWith("native_",StringComparison.Ordinal)) { resolved.Add(id.DeepClone()); continue; }
            var source=Resolve(session,id.ToString());
            var folder=uploads.Folder(session); Directory.CreateDirectory(folder);
            var path=System.IO.Path.Combine(folder,Hash(id.ToString())+"_"+System.IO.Path.GetFileName(source.Name));
            if(!File.Exists(path)) {
                var temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
                try {
                    if(source.Bytes!=null) File.WriteAllBytes(temp,source.Bytes); else File.Copy(source.Path,temp);
                    File.Move(temp,path,true);
                } finally { if(File.Exists(temp)) File.Delete(temp); }
            }
            resolved.Add(JsonSerializer.SerializeToNode(uploads.Metadata(session,path))!["id"]!.ToString());
        }
        body["attachment_ids"]=resolved;
        return JsonSerializer.SerializeToElement(body);
    }
    public JsonElement PrepareRun(JsonElement body,string key) => uploads.PrepareRun(ResolveInput(body),key);
    public JsonElement PrepareSteer(JsonElement body,string session,string key) {
        if(!body.TryGetProperty("session_id",out var sid) || sid.GetString()!=session) throw new ArgumentException("附件不属于当前任务的会话");
        return uploads.PrepareHermesSteer(ResolveInput(body),session,key);
    }
}
