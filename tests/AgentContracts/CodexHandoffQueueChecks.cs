using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexHandoffQueueChecks
{
    public static async Task Run() {
        var builder=WebApplication.CreateBuilder();builder.Logging.ClearProviders();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        await using var host=builder.Build();
        var phase="queued";var reads=0;var finished=0;
        host.MapGet("/runs/fixture",()=>{Interlocked.Increment(ref reads);return new{run_id="fixture",session_id="session",status=phase};});
        await host.StartAsync();
        var address=host.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
        // No process IDs: this fixture can never retire a real bridge or CLI.
        using var handoff=new CodexHandoff(new JsonObject{["port"]=new Uri(address).Port,["runs"]=new JsonObject{["fixture"]="session"}},"fixture-token",(_,_)=>Interlocked.Increment(ref finished));
        if(!await handoff.SessionBusy("session") || !handoff.Contains("fixture"))throw new Exception("queued session lost its handoff owner");
        var context=new DefaultHttpContext{RequestServices=host.Services};context.Request.Method="GET";context.Response.Body=new MemoryStream();
        if(!await handoff.Forward(context,"runs/fixture") || !handoff.Contains("fixture"))throw new Exception("polling a queued run retired the owner");
        var retiring=handoff.Retire();
        for(var attempt=0;Volatile.Read(ref reads)<3 && attempt<50;attempt++)await Task.Delay(20);
        if(!handoff.Contains("fixture") || finished!=0)throw new Exception("retirement treated queued as terminal");
        phase="completed";
        await retiring.WaitAsync(TimeSpan.FromSeconds(8));
        if(handoff.Contains("fixture") || finished!=1)throw new Exception("completed queue receipt did not retire exactly once");
        await host.StopAsync();
        Console.WriteLine("Codex handoff queue checks passed: busy session, polling, retirement and terminal receipt");
    }
}
