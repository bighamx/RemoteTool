using Hangfire;
using Hangfire.Common;
using Hangfire.Storage;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using ChuckieHelper.WebApi.Services;
using System.Text.Json;

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
        // Queue DTOs contain Job.Type/Method reflection objects whenever jobs are queued.
        // Expose JSON-safe summaries, not Hangfire's internal invocation representation.
        return Ok(new { stats=monitor.GetStatistics(), servers=monitor.Servers(),
            queues=monitor.Queues().Select(queue=>new {queue.Name,queue.Length,queue.Fetched,
                firstJobs=queue.FirstJobs?.Select(job=>Describe(job.Key,job.Value?.Job)).ToArray()}),
            recurring=connection.GetRecurringJobs().Select(x=>new {x.Id,x.Cron,x.TimeZoneId,x.Queue,x.LastJobId,x.LastJobState,x.LastExecution,x.NextExecution,x.Error}) });
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
        using var connection=JobStorage.Current.GetConnection();
        var state=connection.GetStateData(id);
        var queue=state!=null && state.Data.TryGetValue("Queue",out var queueName)?queueName:null;
        var parameters=detail.Job?.Method.GetParameters();
        return Ok(new {id,detail.CreatedAt,detail.ExpireAt,type=detail.Job?.Type.FullName,method=detail.Job?.Method.Name,
            state=state?.Name,queue,
            arguments=detail.Job?.Args.Select(x=>x?.ToString()),
            parameters=detail.Job?.Args.Select((value,index)=>new {name=parameters?.ElementAtOrDefault(index)?.Name ?? $"arg{index}",
                type=parameters?.ElementAtOrDefault(index)?.ParameterType.Name,value=ParameterValue(value)}),
            attempts=detail.History.Where(x=>x.StateName=="Processing").OrderByDescending(x=>x.CreatedAt).Select(x=>new {id=HangfireConsoleReader.AttemptId(HangfireConsoleReader.AttemptStarted(x)),startedAt=HangfireConsoleReader.AttemptStarted(x)}).DistinctBy(x=>x.id),
            history=detail.History.OrderBy(x=>x.CreatedAt),properties=detail.Properties});
    }
    private static object ParameterValue(object value) {
        if(value==null)return null;
        try{return JsonSerializer.SerializeToElement(value,value.GetType(),new JsonSerializerOptions{MaxDepth=16});}
        catch(Exception error) when(error is JsonException or NotSupportedException){return value.ToString();}
    }
    [HttpGet("jobs/{id}/console")]
    public IActionResult ConsoleLog(string id,[FromQuery]string attempt=null,[FromQuery]int? offset=null,[FromQuery]int count=200)
    {
        var detail=JobStorage.Current.GetMonitoringApi().JobDetails(id);
        if(detail==null)return NotFound(new{message="Job not found"});
        var attempts=detail.History.Where(x=>x.StateName=="Processing").OrderByDescending(x=>x.CreatedAt).ToList();
        var selected=attempt==null?attempts.FirstOrDefault():attempts.FirstOrDefault(x=>HangfireConsoleReader.AttemptId(HangfireConsoleReader.AttemptStarted(x))==attempt);
        if(selected==null) return attempt!=null?NotFound(new{message="Execution attempt not found"}):Ok(new{attempt=(string)null,offset=0,nextOffset=0,total=0,hasMore=false,reset=false,data=Array.Empty<object>(),progress=(double?)null});
        using var connection=JobStorage.Current.GetConnection();
        return Ok(HangfireConsoleReader.Read(connection,id,HangfireConsoleReader.AttemptStarted(selected),offset,count));
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
