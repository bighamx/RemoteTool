namespace ChuckieHelper.WebApi.Jobs;

public static class LegacyJobFixture
{
    public static void Run() => throw new InvalidOperationException("Serialization fixture must never execute.");
}
