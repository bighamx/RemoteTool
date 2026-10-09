namespace RemoteTool.Lib.Tool;

/// <summary>
/// Hangfire Cron 表达式常量扩展
/// </summary>
public static class HangfireCron
{
    /// <summary>
    /// 永不自动触发（2月31日），仅支持手动触发
    /// </summary>
    public const string Never = "0 0 31 2 *";
}
