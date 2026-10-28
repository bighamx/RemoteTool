using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Cloudflare.Kit;
using Cloudflare.NET.Core;
using Cloudflare.NET.R2.Configuration;

namespace RemoteTool.WebApi.Services.Cloudflare;

public sealed record CloudflareAccountInput(string Id, string Name, string AccountId, string ApiToken,
    string AccessKeyId, string SecretAccessKey, string Bucket, bool ClearR2 = false);
public sealed record CloudflareAccount(string Id, string Name, string AccountId, string ApiToken,
    string AccessKeyId, string SecretAccessKey, string Bucket);

public sealed class CloudflareAccounts {
    private readonly object gate = new();
    private readonly string path;
    private readonly byte[] key;
    private readonly Func<CloudflareAccount,CloudflareKitClient> clientFactory;
    public CloudflareAccounts(IConfiguration configuration) : this(
        Path.Combine(RemoteToolPaths.ProgramData,"cloudflare","accounts.json"),
        configuration["Auth:JwtSecret"] ?? throw new InvalidOperationException("Auth:JwtSecret is required")) { }
    internal CloudflareAccounts(string path, string secret,Func<CloudflareAccount,CloudflareKitClient> clientFactory=null) {
        this.path = path;
        this.clientFactory=clientFactory;
        key = SHA256.HashData(Encoding.UTF8.GetBytes("RemoteTool Cloudflare accounts v1\0"+secret));
    }
    private List<CloudflareAccount> Read() => File.Exists(path)
        ? JsonSerializer.Deserialize<List<CloudflareAccount>>(File.ReadAllText(path)) ?? [] : [];
    public object[] List() { lock(gate) return Read().Select(row=>(object)new {
        row.Id,row.Name,row.AccountId,row.Bucket,hasToken=row.ApiToken.Length>0,hasR2=row.AccessKeyId.Length>0&&row.SecretAccessKey.Length>0 }).ToArray(); }
    private string Protect(string text) {
        if(string.IsNullOrEmpty(text))return "";
        var nonce=RandomNumberGenerator.GetBytes(12);var bytes=Encoding.UTF8.GetBytes(text);var cipher=new byte[bytes.Length];var tag=new byte[16];
        using(var aes=new AesGcm(key,16))aes.Encrypt(nonce,bytes,cipher,tag);
        return Convert.ToBase64String(nonce.Concat(tag).Concat(cipher).ToArray());
    }
    private string Open(string text) {
        if(string.IsNullOrEmpty(text))return "";
        var bytes=Convert.FromBase64String(text);if(bytes.Length<28)throw new InvalidDataException("Cloudflare 凭据已损坏");
        var plain=new byte[bytes.Length-28];using(var aes=new AesGcm(key,16))aes.Decrypt(bytes.AsSpan(0,12),bytes.AsSpan(28),bytes.AsSpan(12,16),plain);
        return Encoding.UTF8.GetString(plain);
    }
    private void Write(List<CloudflareAccount> rows) {
        IdentityDataDirectory.Ensure(Path.GetDirectoryName(path)!);
        var temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try { File.WriteAllText(temporary,JsonSerializer.Serialize(rows));IdentityDataDirectory.EnsureFile(temporary);File.Move(temporary,path,true); }
        finally { if(File.Exists(temporary))File.Delete(temporary); }
    }
    public string Save(CloudflareAccountInput input) {
        var name=input.Name?.Trim()??"";var account=input.AccountId?.Trim()??"";
        if(name.Length is <1 or >80 || !Regex.IsMatch(account,"^[a-fA-F0-9]{32}$"))throw new ArgumentException("请填写名称和 32 位 Account ID");
        if(new[]{input.ApiToken,input.AccessKeyId,input.SecretAccessKey}.Any(value=>value?.Length>4096))throw new ArgumentException("凭据过长");
        var bucket=input.Bucket?.Trim()??"";
        if(bucket.Length>0&&!Regex.IsMatch(bucket,"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$"))throw new ArgumentException("桶名称无效");
        lock(gate) {
            var rows=Read();var old=rows.FirstOrDefault(row=>row.Id==input.Id);
            if(!string.IsNullOrEmpty(input.Id)&&old==null)throw new ArgumentException("配置已不存在，请刷新");
            var token=string.IsNullOrWhiteSpace(input.ApiToken)?old?.ApiToken??"":Protect(input.ApiToken.Trim());
            if(token.Length==0)throw new ArgumentException("请填写 API Token");
            var access=input.ClearR2?"":string.IsNullOrWhiteSpace(input.AccessKeyId)?old?.AccessKeyId??"":Protect(input.AccessKeyId.Trim());
            var secret=input.ClearR2?"":string.IsNullOrWhiteSpace(input.SecretAccessKey)?old?.SecretAccessKey??"":Protect(input.SecretAccessKey.Trim());
            if((access.Length==0)!=(secret.Length==0))throw new ArgumentException("R2 Access Key 和 Secret Key 需一起填写");
            var id=old?.Id??Guid.NewGuid().ToString("N");
            rows.RemoveAll(row=>row.Id==id);rows.Add(new(id,name,account,token,access,secret,bucket));Write(rows);return id;
        }
    }
    public void Delete(string id) { lock(gate) { var rows=Read();if(rows.RemoveAll(row=>row.Id==id)==0)throw new KeyNotFoundException();Write(rows); } }
    public CloudflareAccount[] Export() { lock(gate) return Read().Select(row=>Get(row.Id)).ToArray(); }
    public static CloudflareAccount Transient(CloudflareAccountInput input) {
        if(input==null||string.IsNullOrWhiteSpace(input.Name)||input.Name.Length>80||!Regex.IsMatch(input.AccountId??"","^[a-fA-F0-9]{32}$")||string.IsNullOrWhiteSpace(input.ApiToken))throw new ArgumentException("Cloudflare 凭据无效");
        if(new[]{input.ApiToken,input.AccessKeyId,input.SecretAccessKey}.Any(value=>value?.Length>4096))throw new ArgumentException("凭据过长");
        if(string.IsNullOrEmpty(input.AccessKeyId)!=string.IsNullOrEmpty(input.SecretAccessKey))throw new ArgumentException("R2 凭据需一起填写");
        return new(input.Id??"",input.Name,input.AccountId,input.ApiToken,input.AccessKeyId??"",input.SecretAccessKey??"",input.Bucket??"");
    }
    public CloudflareAccount Get(string id) { lock(gate) {
        var row=Read().FirstOrDefault(row=>row.Id==id)??throw new KeyNotFoundException();
        return row with { ApiToken=Open(row.ApiToken),AccessKeyId=Open(row.AccessKeyId),SecretAccessKey=Open(row.SecretAccessKey) };
    } }
    public CloudflareKitClient Client(CloudflareAccount account) => clientFactory?.Invoke(account) ?? new(new CloudflareApiOptions {
        ApiToken=account.ApiToken,AccountId=account.AccountId,DefaultTimeout=TimeSpan.FromSeconds(35)
    },account.SecretAccessKey.Length==0?null:new R2Settings { AccessKeyId=account.AccessKeyId,SecretAccessKey=account.SecretAccessKey });
}
