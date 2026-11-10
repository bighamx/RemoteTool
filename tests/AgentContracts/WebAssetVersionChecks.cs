using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using RemoteTool.WebApi.Services;

internal static class WebAssetVersionChecks
{
    internal static async Task Run()
    {
        var root = Path.Combine(Path.GetTempPath(), "remotetool-web-assets-" + Guid.NewGuid().ToString("N"));
        var webRoot = Path.Combine(root, "wwwroot");
        var scripts = Path.Combine(webRoot, "js");
        Directory.CreateDirectory(scripts);
        var script = Path.Combine(scripts, "fixture.js");
        await File.WriteAllTextAsync(script, "const revision=1;");
        try {
            var builder = WebApplication.CreateBuilder(new WebApplicationOptions {
                ContentRootPath = root, WebRootPath = "wwwroot", Args = []
            });
            builder.Services.AddControllersWithViews();
            builder.Services.AddSingleton<WebAssetVersion>();
            await using var app = builder.Build();
            var versions = app.Services.GetRequiredService<WebAssetVersion>();
            var initial = versions.Value;
            if (initial != versions.Value) throw new Exception("Unchanged assets must retain their cache version");

            // Keep the same file length so the version has to account for its content.
            await File.WriteAllTextAsync(script, "const revision=2;");
            var edited = await WaitForChange(versions, initial);
            if (edited != versions.Value) throw new Exception("Live-edited assets must have a stable new version");

            var css = Path.Combine(webRoot, "fixture.css");
            await File.WriteAllTextAsync(css, "body{color:green}");
            var added = await WaitForChange(versions, edited);
            File.Delete(css);
            var removed = await WaitForChange(versions, added);
            if (removed != edited) throw new Exception("The same asset contents must produce the same version");
            Console.WriteLine("Web assets: stable caching, live edits, additions and deletions passed without restarting the host");
        } finally {
            Directory.Delete(root, true);
        }
    }

    private static async Task<string> WaitForChange(WebAssetVersion versions, string previous)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        while (versions.Value == previous) await Task.Delay(50, timeout.Token);
        return versions.Value;
    }
}
