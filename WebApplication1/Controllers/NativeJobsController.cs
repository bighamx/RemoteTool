using Hangfire;
using Hangfire.Common;
using Hangfire.Storage;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace ChuckieHelper.WebApi.Controllers;

[Authorize]
[ApiController]
[Route("api/native-jobs")]
public class NativeJobsController : ControllerBase
{
    private static object Describe(string id, Job job) => new { id, type = job?.Type.FullName, method = job?.Method.Name };

    [HttpGet("overview")]
    public IActionResult Overview()
    {
        var monitor=JobStorage.Current.GetMonitoringApi();
        using var connection=JobStorage.Current.GetConnection();
        return Ok(new { stats=monitor.GetStatistics(), servers=monitor.Servers(), queues=monitor.Queues(),
            recurring=connection.GetRecurringJobs().Select(x=>new {x.Id,x.Cron,x.TimeZoneId,x.Queue,x.LastJobState,x.LastExecution,x.NextExecution,x.Error}) });
    }
    [HttpGet("jobs")]
    public IActionResult Jobs([FromQuery]string state="failed",[FromQuery]string queue="default",[FromQuery]int offset=0,[FromQuery]int count=30)
    {
        var monitor=JobStorage.Current.GetMonitoringApi();offset=Math.Max(0,offset);count=Math.Clamp(count,1,100);
        IEnumerable<object> rows=state.ToLowerInvariant() switch {
            "failed"=>monitor.FailedJobs(offset,count).Select(x=>Describe(x.Key,x.Value.Job)),
            "processing"=>monitor.ProcessingJobs(offset,count).Select(x=>Describe(x.Key,x.Value.Job)),
            "scheduled"=>monitor.ScheduledJobs(offset,count).Select(x=>Describe(x.Key,x.Value.Job)),
            "succeeded"=>monitor.SucceededJobs(offset,count).Select(x=>Describe(x.Key,x.Value.Job)),
            "deleted"=>monitor.DeletedJobs(offset,count).Select(x=>Describe(x.Key,x.Value.Job)),
            "enqueued"=>monitor.EnqueuedJobs(queue,offset,count).Select(x=>Describe(x.Key,x.Value.Job)),
            _=>null
        };
        return rows==null?BadRequest(new{message="Unsupported job state"}):Ok(new{data=rows});
    }
    [HttpGet("jobs/{id}")]
    public IActionResult Detail(string id)
    {
        var detail=JobStorage.Current.GetMonitoringApi().JobDetails(id);
        if(detail==null)return NotFound(new{message="Job not found"});
        return Ok(new {id,detail.CreatedAt,detail.ExpireAt,type=detail.Job?.Type.FullName,method=detail.Job?.Method.Name,
            arguments=detail.Job?.Args.Select(x=>x?.ToString()),history=detail.History,properties=detail.Properties});
    }
    [HttpPost("jobs/{id}/retry")]
    public IActionResult Retry(string id)=>BackgroundJob.Requeue(id)?Ok(new{message="Job requeued"}):BadRequest(new{message="Cannot requeue job"});
    [HttpPost("jobs/{id}/delete")]
    public IActionResult Delete(string id)=>BackgroundJob.Delete(id)?Ok(new{message="Job deleted"}):BadRequest(new{message="Cannot delete job"});
    [HttpPost("recurring/{id}/delete")]
    public IActionResult DeleteRecurring(string id){RecurringJob.RemoveIfExists(id);return Ok(new{message="Recurring job removed"});}
    [HttpPost("recurring/{id}/update")]
    public IActionResult UpdateRecurring(string id,[FromBody]RecurringUpdate request)
    {
        using var connection=JobStorage.Current.GetConnection();var existing=connection.GetRecurringJobs().FirstOrDefault(x=>x.Id==id);
        if(existing==null)return NotFound(new{message="Recurring job not found"});
        try {
            var zone=TimeZoneInfo.FindSystemTimeZoneById(request.TimeZoneId);
            new RecurringJobManager().AddOrUpdate(id,existing.Job,request.Cron,new RecurringJobOptions{TimeZone=zone,QueueName=existing.Queue});
            return Ok(new{message="Schedule updated"});
        }catch(ArgumentException ex){return BadRequest(new{message=ex.Message});}catch(TimeZoneNotFoundException ex){return BadRequest(new{message=ex.Message});}
    }
    public sealed record RecurringUpdate(string Cron,string TimeZoneId);
}
