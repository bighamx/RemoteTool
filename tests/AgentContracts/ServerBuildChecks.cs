using RemoteTool.WebApi.Services;

internal static class ServerBuildChecks {
    internal static void Run() {
        var hash = "0123456789abcdef0123456789abcdef01234567";
        if (ServerBuild.ShortVersion(hash) != "0123456789ab" ||
            ServerBuild.ShortVersion(hash + "-dirty") != "0123456789ab-dirty" ||
            ServerBuild.ShortVersion("1.0.0+" + hash) != "0123456789ab" ||
            ServerBuild.ShortVersion("20261102.0110") != "unknown" ||
            ServerBuild.ShortVersion("unknown") != "unknown")
            throw new Exception("Server version must identify source code, including dirty builds");
        Console.WriteLine("Server source revision display checks passed");
    }
}
