using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Mvc.ViewFeatures;
using Microsoft.Extensions.Caching.Memory;

namespace RemoteTool.WebApi.Services;

/// <summary>Shares a content version across lazy assets and invalidates it after live edits.</summary>
public sealed class WebAssetVersion(IWebHostEnvironment environment, IMemoryCache cache, IFileVersionProvider fileVersions)
{
    public string Value => cache.GetOrCreate(this, entry => {
        // Register before reading so a concurrent edit cannot leave this entry stale.
        entry.AddExpirationToken(environment.WebRootFileProvider.Watch("**/*"));
        if (string.IsNullOrEmpty(environment.WebRootPath) || !Directory.Exists(environment.WebRootPath))
            return ServerBuild.Version;

        var versions = new StringBuilder(ServerBuild.Version);
        foreach (var file in Directory.EnumerateFiles(environment.WebRootPath, "*", SearchOption.AllDirectories)
            .Where(path => Path.GetExtension(path).Equals(".js", StringComparison.OrdinalIgnoreCase)
                || Path.GetExtension(path).Equals(".css", StringComparison.OrdinalIgnoreCase))
            .Order(StringComparer.Ordinal)) {
            var path = "/" + Path.GetRelativePath(environment.WebRootPath, file).Replace('\\', '/');
            versions.Append('|').Append(fileVersions.AddFileVersionToPath(default, path));
        }
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(versions.ToString())))[..16].ToLowerInvariant();
    });
}
