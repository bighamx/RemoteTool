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
        // 正式发行包显示语义版本号，开发构建才显示 hash
        if (ServerBuild.Describe("1.2.2+" + hash, 2) != "1.2.2" ||
            ServerBuild.Describe("1.2.1+" + hash, 0) != "0123456789ab" ||
            ServerBuild.Describe("1.2.2", 3) != "1.2.2" ||
            ServerBuild.Describe("unknown", 0) != "unknown")
            throw new Exception("Release builds must display the semantic version");
        Console.WriteLine("Server source revision display checks passed");
    }
}
