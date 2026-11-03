using System.Reflection;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

internal static class CodexShutdownChecks {
    public static async Task Run(Assembly assembly) {
        var root=Path.Combine(Path.GetTempPath(),"codex-shutdown-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        var config=Path.Combine(root,"connection.json");File.WriteAllText(config,new JsonObject{["home"]=root,["state_folder"]=root,["token"]="fixture-only"}.ToJsonString());
        var type=assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent",true)!;
        var agent=(IAsyncDisposable)type.GetConstructor(BindingFlags.NonPublic|BindingFlags.Instance,null,[typeof(string)],null)!.Invoke([config]);
        var serve=type.GetMethod("Serve",BindingFlags.NonPublic|BindingFlags.Instance)!;
        var builder=WebApplication.CreateSlimBuilder(new WebApplicationOptions{Args=Array.Empty<string>(),ContentRootPath=root});
        builder.Logging.ClearProviders();builder.WebHost.UseKestrel().UseUrls("http://127.0.0.1:0");
        await using var app=builder.Build();app.Run(context=>(Task)serve.Invoke(agent,[context])!);
        try {
            await app.StartAsync();var address=app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
            var stopped=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            using var registration=app.Lifetime.ApplicationStopping.Register(()=>stopped.TrySetResult());
            using var client=new HttpClient();client.DefaultRequestHeaders.Authorization=new("Bearer","fixture-only");
            using var reply=await client.PostAsync(address+"/maintenance/shutdown",null);
            reply.EnsureSuccessStatusCode();
            await stopped.Task.WaitAsync(TimeSpan.FromSeconds(3));
            Console.WriteLine("PASS: real HTTP graceful shutdown signals application lifetime after accepting response");
        } finally {await app.StopAsync();await agent.DisposeAsync();Directory.Delete(root,true);}
    }
}
