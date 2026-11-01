using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services;

/// <summary>RemoteTool availability only; credentials stay in the native provider configuration.</summary>
public sealed class AgentProviderPolicy
{
    private static readonly object Gate=new();
    private readonly string path;
    public AgentProviderPolicy(string agent,string profile="default") : this(Path.Combine(RemoteToolPaths.ProgramData,"provider-policy",agent,profile+".json"),true) {
        if(agent is not ("codex" or "hermes") || !Regex.IsMatch(profile,"^[a-zA-Z0-9_-]{1,80}$")) throw new ArgumentException("无效 Provider 范围");
    }
    internal AgentProviderPolicy(string path,bool directPath){this.path=path;}
    private JsonObject Read()=>File.Exists(path)?JsonNode.Parse(File.ReadAllText(path))!.AsObject():new();
    private string Key(string id)=>id;
    public bool HasDisabled {get{lock(Gate)return Read().Count>0;}}
    public bool Enabled(string id){lock(Gate)return Read()[Key(id)]?.GetValue<bool>()!=false;}
    public void Set(string id,bool enabled){
        if(string.IsNullOrWhiteSpace(id)||id.Length>160||id.Any(char.IsControl))throw new ArgumentException("无效 Provider 标识");
        lock(Gate){id=Key(id);var data=Read();if(enabled)data.Remove(id);else data[id]=false;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
            try{File.WriteAllText(temporary,data.ToJsonString());File.Move(temporary,path,true);}finally{if(File.Exists(temporary))File.Delete(temporary);}
        }
    }
}
