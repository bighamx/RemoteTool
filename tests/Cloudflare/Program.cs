using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Reflection;
using Amazon.S3;
using Amazon.S3.Model;
using Cloudflare.Kit;
using Cloudflare.Kit.Tunnels;
using Cloudflare.Kit.Workers;
using Cloudflare.NET;
using Cloudflare.NET.Core;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using RemoteTool.WebApi.Controllers;
using RemoteTool.WebApi.Services.Cloudflare;

var fixture=Path.Combine(Path.GetTempPath(),"remotetool-cf-test-"+Guid.NewGuid().ToString("N"));
var config=Path.Combine(fixture,"accounts.json");
var handler=new MockCloudflare();using var http=new HttpClient(handler);
var options=new CloudflareApiOptions {ApiToken="test-secret-token",AccountId=new string('a',32),ApiBaseUrl="https://api.cloudflare.test/client/v4/"};
http.BaseAddress=new Uri(options.ApiBaseUrl);
using var s3=new MockS3();var s3Mock=s3;
CloudflareKitClient Client(CloudflareAccount _) {
    var transport=new CloudflareTransport(http,options);
    return new(new CloudflareApiClient(http,Options.Create(options),NullLoggerFactory.Instance),new TunnelClient(transport),new WorkerScriptsClient(transport),new Cloudflare.NET.R2.R2Client(NullLoggerFactory.Instance,s3));
}
var store=new CloudflareAccounts(config,"test-encryption-key",Client);
var checks=0;
void Check(bool value,string name){checks++;if(!value)throw new Exception(name);}
var input=new CloudflareAccountInput(null,"Test",new string('a',32),"test-secret-token","test-access-key","test-r2-secret","my-bucket");
try {
    var id=store.Save(input);
    Check(!File.ReadAllText(config).Contains("test-secret-token")&&!File.ReadAllText(config).Contains("test-r2-secret"),"secrets encrypted at rest");
    var publicJson=JsonSerializer.Serialize(store.List());
    Check(!publicJson.Contains("test-secret-token")&&!publicJson.Contains("test-access-key"),"configuration API never returns secrets");
    store.Save(input with {Id=id,Name="Renamed",ApiToken="",AccessKeyId="",SecretAccessKey=""});
    Check(store.Get(id).ApiToken=="test-secret-token"&&store.Get(id).SecretAccessKey=="test-r2-secret","blank credentials preserve saved values");
    var reloaded=new CloudflareAccounts(config,"test-encryption-key",Client);
    Check(reloaded.Get(id).Name=="Renamed","configuration survives service restart");
    try{store.Save(input with{AccountId="bad"});throw new Exception("invalid account accepted");}catch(ArgumentException){checks++;}
    var controller=new CloudflareController(store){ControllerContext=new ControllerContext{HttpContext=new DefaultHttpContext()}};
    async Task<JsonNode> Call(string operation,object extra=null) {
        var body=extra==null?new JsonObject():JsonSerializer.SerializeToNode(extra).AsObject();body["operation"]=operation;
        var result=await controller.Execute(id,JsonSerializer.SerializeToElement(body),default);
        if(result is not OkObjectResult ok)throw new Exception(operation+": "+JsonSerializer.Serialize((result as ObjectResult)?.Value));
        return JsonSerializer.SerializeToNode(ok.Value,new JsonSerializerOptions(JsonSerializerDefaults.Web))["data"];
    }
    var zones=await Call("zones");Check(zones["items"][0]["name"].ToString()=="example.com","zone list SDK response");
    var beforeInline=File.ReadAllText(config);
    var inlineBody=JsonSerializer.SerializeToElement(new {operation="zones",account=new {id="cloud-only",name="Cloud",accountId=new string('a',32),apiToken="cloud-token",accessKeyId="",secretAccessKey="",bucket=""}});
    Check(await controller.ExecuteInline(inlineBody,default) is OkObjectResult,"cloud credentials execute without a server account id");
    Check(File.ReadAllText(config)==beforeInline,"inline cloud credentials are never persisted on the computer");
    var exported=store.Export();
    Check(exported.Length==1&&exported[0].ApiToken=="test-secret-token"&&exported[0].SecretAccessKey=="test-r2-secret","legacy migration exports decrypted credentials without changing originals");
    Check(await controller.ExecuteInline(JsonSerializer.SerializeToElement(new {operation="zones",account=new{ name="bad",accountId="bad",apiToken="x"}}),default) is BadRequestObjectResult,"invalid inline credentials rejected");
    await Call("dns.list",new{zone=new string('b',32),page=2});Check(handler.LastPath.Contains("page=2"),"DNS page forwarded");
    await Call("dns.list",new{zone=new string('b',32),recordType="TXT",proxy="dns",sort="content",direction="desc"});
    Check(handler.LastPath.Contains("type=TXT")&&handler.LastPath.Contains("proxied=false")&&handler.LastPath.Contains("direction=desc")&&handler.LastPath.Contains("order=content"),"DNS type, proxy and sorting apply upstream to all pages");
    var searched=await Call("dns.list",new{zone=new string('b',32),query="VERIFICATION"});
    Check(searched["items"].AsArray().Count==1&&searched["total"].GetValue<int>()==1,"DNS free-text search matches record contents case-insensitively");
    await Call("dns.save",new{zone=new string('b',32),record="record",type="TXT",name="test.example.com",content="a+b/#%",ttl=1,proxied=false});
    Check(handler.LastMethod=="PATCH"&&JsonNode.Parse(handler.LastBody)["content"].ToString()=="a+b/#%","DNS patch preserves untouched fields and exact content");
    var db=await Call("d1.list");Check(db["items"].AsArray().Count==1,"D1 list");
    var buckets=await Call("r2.buckets");Check(buckets["items"][0]["name"].ToString()=="my-bucket","R2 bucket SDK listing");
    var listing=await Call("r2.list",new{bucket="my-bucket",prefix="dir/",cursor="cursor+#%"});
    Check(listing["items"].AsArray().Count==2&&listing["items"][0]["directory"].GetValue<bool>(),"R2 groups same directory once while retaining files");
    Check(s3Mock.LastList.Prefix=="dir/"&&s3Mock.LastList.ContinuationToken=="cursor+#%"&&listing["cursor"].ToString()=="next+#%","R2 cursor and unusual object characters preserved");
    var bytes=Encoding.UTF8.GetBytes("file contents");using var uploadStream=new MemoryStream(bytes);
    var uploadResult=await controller.Upload(id,"my-bucket","dir/a +#%.txt",new FormFile(uploadStream,0,bytes.Length,"file","test.txt"),default);
    Check(uploadResult is OkObjectResult&&s3Mock.UploadedKey=="dir/a +#%.txt"&&s3Mock.Uploaded.SequenceEqual(bytes),"R2 upload uses the SDK and streams the exact file");
    var inlineAccount=inlineBody.GetProperty("account").GetRawText();
    using var cloudUpload=new MemoryStream(bytes);
    Check(await controller.UploadInline(inlineAccount,"my-bucket","cloud/file.txt",new FormFile(cloudUpload,0,bytes.Length,"file","file.txt"),default) is OkObjectResult,"cloud R2 upload works without a stored server account");
    var cloudDownload=await controller.DownloadInline(JsonSerializer.SerializeToElement(new {account=inlineBody.GetProperty("account"),bucket="my-bucket",key="cloud/file.txt"}),default);
    Check(cloudDownload is PhysicalFileResult&&File.ReadAllText(((PhysicalFileResult)cloudDownload).FileName)=="download data","cloud R2 download accepts credentials in POST body");
    File.Delete(((PhysicalFileResult)cloudDownload).FileName);
    Check(File.ReadAllText(config)==beforeInline,"R2 cloud requests leave server credentials unchanged");
    var download=await controller.Download(id,"my-bucket","dir/a +#%.txt",default);
    Check(download is PhysicalFileResult&&File.ReadAllText(((PhysicalFileResult)download).FileName)=="download data","R2 downloads stream via the SDK to a temporary file");
    File.Delete(((PhysicalFileResult)download).FileName);
    await Call("r2.delete",new{bucket="my-bucket",key="dir/a +#%.txt"});
    Check(s3Mock.DeletedKey=="dir/a +#%.txt","R2 delete targets the exact object key");
    await Call("d1.rows",new{database="db",table="odd\"; DROP TABLE x;--",offset=200});
    var query=JsonNode.Parse(handler.LastBody);
    Check(query["sql"].ToString()=="SELECT * FROM \"odd\"\"; DROP TABLE x;--\" LIMIT 100 OFFSET ?"&&query["params"][0].GetValue<int>()==200,"table identifiers quoted; offset parameterized");
    await Call("d1.query",new{database="db",sql="SELECT ?",parameters=new object[]{"a'b",7,true,null}});
    Check(JsonNode.Parse(handler.LastBody)["params"].AsArray().Count==4,"SQL parameters forwarded without string concatenation");
    var tunnels=await Call("tunnels.list");Check(tunnels["items"][0]["name"].ToString()=="test","Tunnel SDK list");
    var detail=await Call("tunnels.detail",new{tunnel="tunnel"});Check(detail["tunnel"]["id"].ToString()=="tunnel"&&detail["connections"].AsArray().Count==1,"Tunnel detail and connector list");
    Check(detail["connections"][0]["id"].ToString()=="connector"&&detail["connections"][0]["conns"][0]["colo_name"].ToString()=="HKG"&&detail["connections"][0]["version"].ToString()=="2026.10.0","Nested connector fields survive SDK and controller serialization");
    var token=await Call("tunnels.token",new{tunnel="tunnel"});Check(token["token"].ToString()=="mock-connector-token","Tunnel token endpoint");
    var created=await Call("tunnels.create",new{name="new"});Check(created["id"].ToString()=="tunnel","Tunnel create request through SDK");
    await Call("tunnels.rename",new{tunnel="tunnel",name="renamed"});Check(JsonNode.Parse(handler.LastBody)["name"].ToString()=="renamed","Tunnel rename through SDK");
    await Call("tunnels.saveConfig",new{tunnel="tunnel",config=new{ingress=new[]{new{service="http_status:404"}},unknown_future_field=new{enabled=true}}});
    Check(JsonNode.Parse(handler.LastBody)["config"]["unknown_future_field"]["enabled"].GetValue<bool>(),"Tunnel edits preserve extension fields");
    var count=handler.Calls;
    var invalid=await controller.Execute(id,JsonSerializer.SerializeToElement(new{operation="tunnels.saveConfig",tunnel="tunnel",config=new{ingress=new[]{new{hostname="example.com",service="http://localhost:80"}}}}),default);
    Check(invalid is BadRequestObjectResult&&handler.Calls==count,"missing catch-all rejected before network write");
    store.Save(input with{Id=id,ApiToken="",AccessKeyId="",SecretAccessKey="",ClearR2=true});
    Check(store.Get(id).AccessKeyId==""&&store.Get(id).SecretAccessKey=="","R2 credentials can be removed together");
    store.Delete(id);Check(store.List().Length==0,"account removed");
    Check(typeof(CloudflareController).IsDefined(typeof(Microsoft.AspNetCore.Authorization.AuthorizeAttribute),true),"all Cloudflare routes require authentication");
    Console.WriteLine($"Cloudflare contracts: {checks} passed; mock transport only, no live account writes.");
} finally { if(Directory.Exists(fixture))Directory.Delete(fixture,true); }

sealed class MockCloudflare:HttpMessageHandler {
    public int Calls;public string LastPath,LastBody,LastMethod;
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct) {
        Calls++;LastPath=request.RequestUri.PathAndQuery;LastBody=request.Content==null?"":await request.Content.ReadAsStringAsync(ct);LastMethod=request.Method.Method;
        object result=LastPath.Contains("/dns_records") ? request.Method==HttpMethod.Get?(object)new[]{new{id="one",name="www.example.com",type="A",content="192.0.2.1",ttl=1},new{id="two",name="txt.example.com",type="TXT",content="verification-data",ttl=1}}:new{id="record",name="test.example.com",type="TXT",content="a+b/#%",ttl=1}
            :LastPath.Contains("/d1/database") ? LastPath.EndsWith("/query")?new[]{new{success=true,results=new[]{new{value=1}},meta=new{duration=1.0,rows_read=1,rows_written=0}}}:(object)new[]{new{uuid="db",name="database"}}
            :LastPath.Contains("/r2/buckets") ? new{buckets=new[]{new{name="my-bucket",creation_date="2026-10-28T00:00:00Z"}}}
            :LastPath.Contains("/configurations") ? new{config=new{ingress=new[]{new{service="http_status:404"}}}}
            :LastPath.Contains("/cfd_tunnel") ? new[]{new{id="tunnel",name="test",status="healthy"}}
            :new[]{new{id=new string('b',32),name="example.com",status="active"}};
        if(LastPath.Contains("/cfd_tunnel/")) {
            if(LastPath.EndsWith("/token"))result="mock-connector-token";
            else if(LastPath.EndsWith("/connections"))result=new[]{new{id="connector",version="2026.10.0",arch="windows_amd64",conns=new[]{new{id="edge",client_id="connector",colo_name="HKG",origin_ip="192.0.2.1"}}}};
            else if(!LastPath.EndsWith("/configurations"))result=new{id="tunnel",name="test",status="healthy"};
        } else if(LastPath.EndsWith("/cfd_tunnel")&&request.Method==HttpMethod.Post)result=new{id="tunnel",name="new",status="inactive"};
        return new(HttpStatusCode.OK){Content=new StringContent(JsonSerializer.Serialize(new{success=true,result,errors=Array.Empty<object>(),messages=Array.Empty<object>(),result_info=new{page=1,per_page=100,total_pages=1,total_count=1}}),Encoding.UTF8,"application/json")};
    }
}

public class MockS3:AmazonS3Client {
    public MockS3():base(new Amazon.Runtime.AnonymousAWSCredentials(),new AmazonS3Config{ServiceURL="https://test.r2.cloudflarestorage.com"}){}
    public ListObjectsV2Request LastList;public string UploadedKey,DeletedKey;public byte[] Uploaded;
    public override Task<ListObjectsV2Response> ListObjectsV2Async(ListObjectsV2Request request,CancellationToken ct=default) {
            LastList=request;
            return Task.FromResult(new ListObjectsV2Response{HttpStatusCode=HttpStatusCode.OK,IsTruncated=true,NextContinuationToken="next+#%",S3Objects=new(){new(){Key=LastList.Prefix+"folder/a.txt",Size=10},new(){Key=LastList.Prefix+"a +#%.txt",Size=12},new(){Key=LastList.Prefix+"folder/b.txt",Size=10}}});
    }
    public override Task<PutObjectResponse> PutObjectAsync(PutObjectRequest request,CancellationToken ct=default) {
            UploadedKey=request.Key;
            using var output=new MemoryStream();request.InputStream.CopyTo(output);Uploaded=output.ToArray();
            return Task.FromResult(new PutObjectResponse{HttpStatusCode=HttpStatusCode.OK,ETag="mock-etag"});
    }
    public override Task<GetObjectResponse> GetObjectAsync(GetObjectRequest request,CancellationToken ct=default) => Task.FromResult(new GetObjectResponse{HttpStatusCode=HttpStatusCode.OK,ResponseStream=new MemoryStream(Encoding.UTF8.GetBytes("download data")),ContentLength=13});
    public override Task<DeleteObjectResponse> DeleteObjectAsync(DeleteObjectRequest request,CancellationToken ct=default) {DeletedKey=request.Key;return Task.FromResult(new DeleteObjectResponse{HttpStatusCode=HttpStatusCode.NoContent});}
}
