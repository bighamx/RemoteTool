using Hangfire.Common;

namespace RemoteTool.WebApi.Services;

/// <summary>Resolve persisted task names from releases preceding the project rename.</summary>
public static class HangfireTypeResolver
{
    public static Type Resolve(string name)
    {
        var parts = name.Split(',', 3, StringSplitOptions.TrimEntries);
        if (parts.Length >= 2 && parts[0].StartsWith("ChuckieHelper.WebApi.Jobs.", StringComparison.Ordinal)
            && parts[1] is "ChuckieHelper.WebApi" or "WebApplication1")
            return typeof(HangfireTypeResolver).Assembly.GetType(parts[0], throwOnError: true)!;
        return TypeHelper.DefaultTypeResolver(name);
    }
}
