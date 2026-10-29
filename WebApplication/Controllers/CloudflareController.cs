using Cloudflare.Kit.Tunnels;
using Cloudflare.Kit;
using Cloudflare.NET.Core.Exceptions;
using Cloudflare.NET.Accounts.D1.Models;
using Cloudflare.NET.Accounts.Models;
using Cloudflare.NET.Dns.Models;
using Cloudflare.NET.Zones.Models;
using Cloudflare.NET.Security.Firewall.Models;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.Cloudflare;
using System.Text.Json;

namespace RemoteTool.WebApi.Controllers;

[ApiController,Authorize,Route("api/cloudflare")]
public sealed class CloudflareController(CloudflareAccounts accounts) : ControllerBase {
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNameCaseInsensitive=true };
    private static readonly JsonSerializerOptions TunnelJson = new(JsonSerializerDefaults.Web) { PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower,PropertyNameCaseInsensitive=true };
    private static string Text(JsonElement body,string key,string fallback="") => body.TryGetProperty(key,out var value)&&value.ValueKind==JsonValueKind.String?value.GetString()??fallback:fallback;
    private static int Number(JsonElement body,string key,int fallback) => body.TryGetProperty(key,out var value)&&value.TryGetInt32(out var number)?number:fallback;
    private static string Required(JsonElement body,string key,int max=1024) {
        var text=Text(body,key);if(string.IsNullOrWhiteSpace(text)||text.Length>max)throw new ArgumentException("参数无效："+key);return text;
    }
    private async Task<IActionResult> Result(Func<Task<object>> action) {
        Response.Headers.CacheControl="no-store";
        try { return Ok(new {data=await action()}); }
        catch(KeyNotFoundException) { return NotFound(new {message="Cloudflare 配置不存在，请刷新"}); }
        catch(ArgumentException error) { return BadRequest(new {message=error.Message}); }
        catch(CloudflareApiException error) { return StatusCode(502,new {message=error.Errors.Count>0?string.Join("；",error.Errors.Select(item=>$"{item.Code}: {item.Message}")):"Cloudflare 请求失败，请检查权限与参数"}); }
        catch(CloudflareKitException error) { return StatusCode(502,new {message=error.Errors.Count>0?string.Join("；",error.Errors.Select(item=>$"{item.Code}: {item.Message}")):$"Cloudflare HTTP {(int)error.StatusCode}"}); }
        catch(OperationCanceledException) when(!HttpContext.RequestAborted.IsCancellationRequested) { return StatusCode(504,new {message="Cloudflare 请求超时，请先核对操作结果再重试"}); }
        catch(Exception error) when(error is not OperationCanceledException) { return StatusCode(502,new {message="Cloudflare 请求失败，请检查凭据、权限与资源状态"}); }
    }
    [HttpGet("accounts")] public Task<IActionResult> Accounts() => Result(()=>Task.FromResult<object>(accounts.List()));
    [HttpPost("accounts")] public Task<IActionResult> Save([FromBody] CloudflareAccountInput input) => Result(()=>Task.FromResult<object>(new {id=accounts.Save(input)}));
    [HttpPost("accounts/{id}/delete")] public Task<IActionResult> Delete(string id) => Result(()=>{accounts.Delete(id);return Task.FromResult<object>(new {ok=true});});
    // One-time migration to the user's cloud configuration; requires the computer's existing authentication.
    [HttpPost("accounts/export")] public Task<IActionResult> Export() => Result(()=>Task.FromResult<object>(accounts.Export()));
    private static CloudflareAccount Inline(JsonElement body) => CloudflareAccounts.Transient(body.GetProperty("account").Deserialize<CloudflareAccountInput>(Json));
    [HttpPost("execute")]
    public Task<IActionResult> ExecuteInline([FromBody] JsonElement body,CancellationToken ct) => Result(()=>ExecuteOperation(Inline(body),body,ct));

    [HttpPost("accounts/{id}/execute")]
    public Task<IActionResult> Execute(string id,[FromBody] JsonElement body,CancellationToken ct) => Result(async ()=>{
        return await ExecuteOperation(accounts.Get(id),body,ct);
    });
    private async Task<object> ExecuteOperation(CloudflareAccount account,JsonElement body,CancellationToken ct) {
        using var client=accounts.Client(account);
        var page=Math.Clamp(Number(body,"page",1),1,10000);
        switch(Required(body,"operation",40)) {
            case "zones": {
                var result=await client.Api.Zones.ListZonesAsync(new ListZonesFilters(Page:page,PerPage:100),ct);
                return new {items=result.Items.Select(row=>new {row.Id,row.Name,status=row.Status.ToString()}),hasMore=result.Items.Count==100};
            }
            case "dns.list": {
                var typeText=Text(body,"recordType").Trim().ToUpperInvariant();
                DnsRecordType? type=typeText.Length==0?(DnsRecordType?)null:new DnsRecordType(typeText);
                bool? proxied=Text(body,"proxy") switch {"proxied"=>true,"dns"=>false,_=>null};
                var order=Text(body,"sort","name");if(order is not ("name" or "type" or "content" or "ttl" or "proxied"))throw new ArgumentException("排序字段无效");
                var direction=Text(body,"direction","asc")=="desc"?ListOrderDirection.Descending:ListOrderDirection.Ascending;
                var filters=new ListDnsRecordsFilters(Type:type,Proxied:proxied,Page:page,PerPage:100,Order:order,Direction:direction);
                var query=Text(body,"query").Trim();if(query.Length>500)throw new ArgumentException("搜索内容过长");
                if(query.Length>0) {
                    // The upstream SDK exposes exact-name filtering, so scan its paged iterator for free-text searches.
                    // Apply type/proxy/order upstream, and never quietly restrict searching to the currently visible page.
                    var matching=new List<DnsRecord>();var scanned=0;
                    await foreach(var record in client.Dns.ListAllDnsRecordsAsync(Required(body,"zone"),filters with {Page=1},ct)) {
                        if(++scanned>5000)throw new ArgumentException("记录较多，请先按类型或代理状态筛选后搜索");
                        if(DnsMatches(record,query))matching.Add(record);
                    }
                    return new {items=matching.Skip((page-1)*100).Take(100),hasMore=matching.Count>page*100,total=matching.Count};
                }
                var result=await client.Dns.ListDnsRecordsAsync(Required(body,"zone"),filters,ct);
                return new {items=result.Items,hasMore=result.Items.Count==100};
            }
            case "dns.save": {
                var type=JsonSerializer.Deserialize<DnsRecordType>(JsonSerializer.Serialize(Required(body,"type",12).Trim().ToUpperInvariant()));
                var zone=Required(body,"zone");var record=Text(body,"record");
                var name=Required(body,"name",253);var content=Required(body,"content",65536);
                var ttl=Number(body,"ttl",1);if(ttl!=1&&(ttl<60||ttl>86400))throw new ArgumentException("TTL 应为 1（自动）或 60–86400 秒");
                bool? proxied=body.TryGetProperty("proxied",out var proxy)&&proxy.ValueKind is JsonValueKind.True or JsonValueKind.False?proxy.GetBoolean():null;
                int? priority=body.TryGetProperty("priority",out var rank)&&rank.TryGetInt32(out var n)?n:null;
                if(type==DnsRecordType.MX && (priority==null||priority<0||priority>65535))throw new ArgumentException("MX 优先级应为 0–65535");
                if(type!=DnsRecordType.A&&type!=DnsRecordType.AAAA&&type!=DnsRecordType.CNAME)proxied=null;
                if(record.Length==0) return await client.Dns.CreateDnsRecordAsync(zone,new CreateDnsRecordRequest(type,name,content,ttl,proxied,Comment:Text(body,"comment"),Priority:priority),ct);
                var existing=await client.Dns.GetDnsRecordAsync(zone,record,ct);
                RequireMutableDns(existing);
                if(type==DnsRecordType.MX) {
                    var old=existing;
                    return await client.Dns.UpdateDnsRecordAsync(zone,record,new UpdateDnsRecordRequest(type,name,content,ttl,proxied,Text(body,"comment"),old.Tags,priority??old.Priority,old.Settings),ct);
                }
                // Patch preserves unedited tags, settings and other type-specific fields.
                return await client.Dns.PatchDnsRecordAsync(zone,record,new PatchDnsRecordRequest(type,name,content,ttl,proxied,Comment:Text(body,"comment")),ct);
            }
            case "dns.delete": {
                var zone=Required(body,"zone");var record=Required(body,"record");
                RequireMutableDns(await client.Dns.GetDnsRecordAsync(zone,record,ct));
                await client.Dns.DeleteDnsRecordAsync(zone,record,ct);return new {ok=true};
            }
            case "r2.buckets": {
                var result=await client.Accounts.Buckets.ListAsync(new ListR2BucketsFilters(PerPage:100,Cursor:Text(body,"cursor",null)),cancellationToken:ct);
                return new {items=result.Items,cursor=result.CursorInfo?.Cursor};
            }
            case "r2.list": {
                if(account.SecretAccessKey.Length==0)throw new ArgumentException("请先配置 R2 Access Key 和 Secret Key");
                var prefix=Text(body,"prefix");var result=await client.R2.ListObjectsPageAsync(Required(body,"bucket"),prefix,200,Text(body,"cursor",null),ct);
                var folders=new HashSet<string>(StringComparer.Ordinal);var objects=new List<object>();
                foreach(var row in result.Data.Objects) {
                    var relative=row.Key[prefix.Length..];var slash=relative.IndexOf('/');
                    if(slash>=0)folders.Add(prefix+relative[..(slash+1)]);
                    else if(relative.Length>0)objects.Add(new {key=row.Key,name=relative,size=row.Size,modified=row.LastModified,directory=false});
                }
                return new {items=folders.OrderBy(folder=>folder,StringComparer.Ordinal).Select(folder=>(object)new {key=folder,name=folder[prefix.Length..],directory=true}).Concat(objects),cursor=result.Data.NextContinuationToken};
            }
            case "r2.delete": await client.R2.DeleteObjectAsync(Required(body,"bucket"),Required(body,"key"),ct);return new {ok=true};
            case "d1.list": {
                var result=await client.D1.ListAsync(new ListD1DatabasesFilters(Page:page,PerPage:100),ct);
                return new {items=result.Items,hasMore=result.Items.Count==100};
            }
            case "d1.create": return await client.D1.CreateAsync(Required(body,"name",100),cancellationToken:ct);
            case "d1.delete": await client.D1.DeleteAsync(Required(body,"database"),ct);return new {ok=true};
            case "d1.tables": return await client.D1.QueryAsync(Required(body,"database"),"SELECT name,type FROM sqlite_schema WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' AND name NOT GLOB '_cf_*' ORDER BY name",cancellationToken:ct);
            case "d1.schema": return await client.D1.QueryAsync(Required(body,"database"),"PRAGMA table_info("+Quote(Required(body,"table"))+ ")",cancellationToken:ct);
            case "d1.rows": return await Query("SELECT * FROM "+Quote(Required(body,"table"))+" LIMIT 100 OFFSET ?",new object[]{Math.Clamp(Number(body,"offset",0),0,10000000)});
            case "d1.query": {
                var sql=Required(body,"sql",32000);object[] parameters=[];
                if(body.TryGetProperty("parameters",out var values)) {
                    if(values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()>100)throw new ArgumentException("parameters 应为 JSON 数组，最多 100 项");
                    parameters=values.EnumerateArray().Select(value=>value.ValueKind switch {
                        JsonValueKind.Null=>null,JsonValueKind.String=>(object)value.GetString(),JsonValueKind.Number=>value.TryGetInt64(out var integer)?(object)integer:value.GetDouble(),
                        JsonValueKind.True=>true,JsonValueKind.False=>false,_=>throw new ArgumentException("参数只支持字符串、数字、布尔或 null") }).ToArray();
                }
                return await Query(sql,parameters);
            }
            case "tunnels.list": {
                var result=await client.Tunnels.ListAsync(account.AccountId,new TunnelListOptions(Page:page),ct);
                return new {items=result.Result,hasMore=result.Result.Count==100};
            }
            case "tunnels.create": return await client.Tunnels.CreateAsync(account.AccountId,Required(body,"name",100),ct);
            case "tunnels.rename": return await client.Tunnels.RenameAsync(account.AccountId,Required(body,"tunnel"),Required(body,"name",100),ct);
            case "tunnels.delete": await client.Tunnels.DeleteAsync(account.AccountId,Required(body,"tunnel"),ct);return new {ok=true};
            case "tunnels.detail": return new {tunnel=await client.Tunnels.GetAsync(account.AccountId,Required(body,"tunnel"),ct),connections=await client.Tunnels.GetConnectionsAsync(account.AccountId,Required(body,"tunnel"),ct)};
            case "tunnels.token": return new {token=await client.Tunnels.GetTokenAsync(account.AccountId,Required(body,"tunnel"),ct)};
            case "tunnels.config": return await client.Tunnels.GetConfigurationAsync(account.AccountId,Required(body,"tunnel"),ct);
            case "tunnels.saveConfig": {
                var config=body.GetProperty("config").Deserialize<TunnelConfig>(TunnelJson)??throw new ArgumentException("配置无效");
                return await client.Tunnels.SetConfigurationAsync(account.AccountId,Required(body,"tunnel"),config,ct);
            }
            case "tunnels.disconnect": await client.Tunnels.RemoveConnectionAsync(account.AccountId,Required(body,"tunnel"),Required(body,"clientId"),ct);return new {ok=true};
            default:throw new ArgumentException("不支持的操作");
        }
        async Task<object> Query(string sql,object[] parameters) {
            var result=await client.D1.QueryAsync(Required(body,"database"),sql,parameters,ct);
            return result.Select(row=>new {row.Success,results=row.Results.Take(200),row.Meta,truncated=row.Results.Count>200}).ToArray();
        }
    }
    internal static string Quote(string name) => "\""+name.Replace("\"","\"\"")+"\"";
    internal static void RequireMutableDns(DnsRecord row) {
        if(row.Meta?.ReadOnly==true)
            throw new ArgumentException(!string.IsNullOrWhiteSpace(row.Meta.OriginWorkerId)
                ? "此 DNS 记录由 Worker 管理且为只读，请前往 Cloudflare 的 Worker → 设置 → 域和路由修改绑定"
                : "此 DNS 记录由 Cloudflare 标记为只读，请前往对应服务的配置页面管理");
    }
    internal static bool DnsMatches(DnsRecord row,string query) => new[]{row.Name,row.Content,row.Comment}.Any(value=>value?.Contains(query,StringComparison.OrdinalIgnoreCase)==true);

    [HttpPost("accounts/{id}/r2/upload"),RequestSizeLimit(100*1024*1024),RequestFormLimits(MultipartBodyLengthLimit=100*1024*1024)]
    public Task<IActionResult> Upload(string id,[FromQuery]string bucket,[FromQuery]string key,IFormFile file,CancellationToken ct) => Result(async ()=>{
        return await UploadFile(accounts.Get(id),bucket,key,file,ct);
    });
    [HttpPost("r2/upload"),RequestSizeLimit(100*1024*1024),RequestFormLimits(MultipartBodyLengthLimit=100*1024*1024)]
    public Task<IActionResult> UploadInline([FromForm]string account,[FromQuery]string bucket,[FromQuery]string key,IFormFile file,CancellationToken ct) => Result(()=>UploadFile(CloudflareAccounts.Transient(JsonSerializer.Deserialize<CloudflareAccountInput>(account,Json)),bucket,key,file,ct));
    private async Task<object> UploadFile(CloudflareAccount account,string bucket,string key,IFormFile file,CancellationToken ct) {
        if(string.IsNullOrEmpty(bucket)||string.IsNullOrEmpty(key)||key.Length>1024||file==null||file.Length<=0)throw new ArgumentException("请选择文件并填写对象路径");
        using var client=accounts.Client(account);await using var stream=file.OpenReadStream();
        await client.R2.UploadAsync(bucket,key,stream,cancellationToken:ct);return new {ok=true};
    }
    [HttpPost("r2/download")]
    public Task<IActionResult> DownloadInline([FromBody] JsonElement body,CancellationToken ct) => DownloadFile(()=>Inline(body),Text(body,"bucket"),Text(body,"key"),ct);
    [HttpGet("accounts/{id}/r2/download")]
    public async Task<IActionResult> Download(string id,[FromQuery]string bucket,[FromQuery]string key,CancellationToken ct) {
        return await DownloadFile(()=>accounts.Get(id),bucket,key,ct);
    }
    private async Task<IActionResult> DownloadFile(Func<CloudflareAccount> resolve,string bucket,string key,CancellationToken ct) {
        // Download to a temporary file so errors stay JSON and large objects never occupy managed memory.
        string temporary=null;
        var handedOff=false;
        try {
        var result=await Result(async ()=>{
            if(string.IsNullOrEmpty(bucket)||string.IsNullOrEmpty(key)||key.Length>1024)throw new ArgumentException("对象路径无效");
            var account=resolve();using var client=accounts.Client(account);
            temporary=Path.Combine(Path.GetTempPath(),"remotetool-r2-"+Guid.NewGuid().ToString("N"));
            await client.R2.DownloadFileAsync(bucket,key,temporary,ct);return new {ok=true};
        });
        if(result is not OkObjectResult) return result;
        Response.OnCompleted(()=>{System.IO.File.Delete(temporary);return Task.CompletedTask;});
        handedOff=true;
        return PhysicalFile(temporary,"application/octet-stream",key.Split('/').LastOrDefault()??"download",enableRangeProcessing:false);
        } finally { if(!handedOff&&temporary!=null&&System.IO.File.Exists(temporary))System.IO.File.Delete(temporary); }
    }
}
