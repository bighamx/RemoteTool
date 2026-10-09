using ChuckieHelper.Lib.Tool;
using Hangfire;
using Hangfire.Console;
using Hangfire.Server;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;

namespace ChuckieHelper.WebApi.Jobs
{
    /// <summary>
    /// 示例任务参数
    /// </summary>
    public record ExampleTaskParams
    {
        /// <summary>
        /// 目标路径
        /// </summary>
        public string TargetPath { get; init; } = string.Empty;
    }

    /// <summary>
    /// 示例任务
    /// </summary>
    public class ExampleTask
    {
        /// <summary>
        /// 执行任务
        /// </summary>
        /// <param name="param">任务参数</param>
        /// <param name="context">Hangfire 上下文（自动注入）</param>
        /// <param name="cancellationToken">取消令牌（自动注入）</param>
        public void Execute(ExampleTaskParams param, PerformContext? context, CancellationToken cancellationToken)
        {
            if (string.IsNullOrWhiteSpace(param.TargetPath))
                return;

            cancellationToken.ThrowIfCancellationRequested();

            try
            {
                context?.WriteLine($"开始处理: {param.TargetPath}");
                var result = Util.SmartUnzipAll(param.TargetPath);
                context?.WriteLine($"完成，共解压 {result.Count} 个文件");
            }
            catch (OperationCanceledException)
            {
                context?.WriteLine("任务已取消");
                throw;
            }
            catch (Exception ex)
            {
                context?.WriteLine($"处理失败: {ex.Message}");
            }
        }
    }

    public static class ExampleTaskExtensions
    {

        public static IServiceCollection AddExampleTask(this IServiceCollection services)
        {
            services.TryAddTransient<ExampleTask>();

            return services;
        }

        public static IHost UseHangfireExampleTask(this IHost app)
        {
            var lifetime = app.Services.GetRequiredService<IHostApplicationLifetime>();


            // 注册为 RecurringJob（仅用于在 Dashboard 显示，使用默认空参数）
            RecurringJob.AddOrUpdate<ExampleTask>(nameof(ExampleTask),
                x => x.Execute(new ExampleTaskParams(), null, CancellationToken.None),
                HangfireCron.Never,
                new RecurringJobOptions
                {
                    TimeZone = TimeZoneInfo.Local,
                    MisfireHandling = MisfireHandlingMode.Ignorable
                });

            return app;
        }
    }
}
