using ChuckieHelper.WebApi.Jobs;
using Hangfire;
using Hangfire.Storage;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace ChuckieHelper.WebApi.Controllers;

/// <summary>
/// 任务管理控制器
/// </summary>
[Authorize]
[ApiController]
[Route("api/[controller]")]
public class JobController : ControllerBase
{
    [HttpGet("overview")]
    public IActionResult Overview()
    {
        using var connection = JobStorage.Current.GetConnection();
        var jobs = connection.GetRecurringJobs().Select(x => new
        {
            x.Id, x.Cron, x.TimeZoneId, x.Queue, x.LastJobState,
            x.LastExecution, x.NextExecution, x.Error
        });
        var stats = JobStorage.Current.GetMonitoringApi().GetStatistics();
        return Ok(new { recurring = jobs, stats });
    }

    [HttpPost("example/trigger")]
    public ActionResult<JobTriggerResult> TriggerExampleTask(string path)
    {
        var jobId = BackgroundJob.Enqueue<ExampleTask>(x => x.Execute(new ExampleTaskParams()
        {
            TargetPath = path
        }, null, CancellationToken.None));
        return Ok(new JobTriggerResult(jobId));
    }

    /// <summary>
    /// 触发任意已注册的 RecurringJob（使用注册时的默认参数）
    /// </summary>
    /// <param name="jobId">任务ID（注册时使用的名称）</param>
    /// <returns>触发结果</returns>
    [HttpPost("{jobId}/trigger")]
    public ActionResult<JobTriggerByNameResult> TriggerJobByName(string jobId)
    {
        try
        {
            RecurringJob.TriggerJob(jobId);
            return Ok(new JobTriggerByNameResult(jobId, true, null));
        }
        catch (Exception ex)
        {
            return NotFound(new JobTriggerByNameResult(jobId, false, ex.Message));
        }
    }
}

/// <summary>
/// 任务触发结果（带参数入队）
/// </summary>
/// <param name="JobId">任务ID</param>
public record JobTriggerResult(string JobId);

/// <summary>
/// 按名称触发任务结果
/// </summary>
/// <param name="JobName">任务名称</param>
/// <param name="Success">是否成功</param>
/// <param name="Error">错误信息</param>
public record JobTriggerByNameResult(string JobName, bool Success, string? Error);
