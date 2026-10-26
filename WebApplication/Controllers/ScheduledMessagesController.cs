using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.Scheduled;

namespace RemoteTool.WebApi.Controllers;

/// <summary>定时消息：提交后由服务端调度器执行，与手机是否在线无关。</summary>
[ApiController, Authorize, Route("api/codex/schedules")]
public sealed class ScheduledMessagesController(ScheduledMessageStore store) : ControllerBase
{
    [HttpGet]
    public IActionResult List([FromQuery] string sessionId, [FromQuery] string status) =>
        Ok(new { items = store.List(sessionId, status).Select(Shape).ToArray() });

    [HttpPost]
    public IActionResult Create([FromBody] JsonElement body)
    {
        if (body.ValueKind != JsonValueKind.Object) return BadRequest(new { message = "请求体必须是 JSON 对象" });
        var sessionId = Text(body, "sessionId");
        var text = Text(body, "text");
        var timeZone = Text(body, "timeZone");
        var clientKey = Text(body, "clientKey");
        if (!TryUnix(body, "sendAt", out var sendAt)) return BadRequest(new { message = "sendAt 必须是 ISO 8601 时间或 Unix 秒" });
        try
        {
            var created = store.Add(sessionId, text, sendAt, timeZone,
                clientKey, DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            return StatusCode(StatusCodes.Status201Created, Shape(created));
        }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }

    [HttpPatch("{id}")]
    public IActionResult Update(string id, [FromBody] JsonElement body)
    {
        string text = null;
        if (body.ValueKind == JsonValueKind.Object && body.TryGetProperty("text", out var value) && value.ValueKind == JsonValueKind.String)
            text = value.GetString();
        long? sendAt = null;
        if (body.ValueKind == JsonValueKind.Object && body.TryGetProperty("sendAt", out _))
        {
            if (!TryUnix(body, "sendAt", out var parsed)) return BadRequest(new { message = "sendAt 必须是 ISO 8601 时间或 Unix 秒" });
            sendAt = parsed;
        }
        try { return Ok(Shape(store.Update(id, text, sendAt, DateTimeOffset.UtcNow.ToUnixTimeSeconds()))); }
        catch (KeyNotFoundException) { return NotFound(new { message = "定时消息不存在" }); }
        catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }

    [HttpDelete("{id}")]
    public IActionResult Delete(string id)
    {
        try { return Ok(Shape(store.Cancel(id, "已取消"))); }
        catch (KeyNotFoundException) { return NotFound(new { message = "定时消息不存在" }); }
        catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }

    private static object Shape(ScheduledMessage message) => new
    {
        id = message.Id,
        agent = message.Agent,
        session_id = message.SessionId,
        text = message.Text,
        send_at = message.SendAt,
        send_at_iso = DateTimeOffset.FromUnixTimeSeconds(message.SendAt).UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ssZ"),
        time_zone = message.TimeZone,
        status = message.Status,
        attempts = message.Attempts,
        last_error = message.LastError,
        created_at = message.CreatedAt,
        sent_at = message.SentAt,
        run_id = message.RunId
    };

    private static string Text(JsonElement body, string name) =>
        body.ValueKind == JsonValueKind.Object && body.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() : "";

    private static bool TryUnix(JsonElement body, string name, out long value)
    {
        value = 0;
        if (body.ValueKind != JsonValueKind.Object || !body.TryGetProperty(name, out var node)) return false;
        if (node.ValueKind == JsonValueKind.Number)
        {
            if (node.TryGetInt64(out value)) return value > 0;
            return false;
        }
        if (node.ValueKind != JsonValueKind.String) return false;
        var raw = node.GetString();
        if (long.TryParse(raw, out value)) return value > 0;
        if (!DateTimeOffset.TryParse(raw, System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.AssumeUniversal | System.Globalization.DateTimeStyles.AdjustToUniversal, out var parsed)) return false;
        value = parsed.ToUnixTimeSeconds();
        return value > 0;
    }
}
