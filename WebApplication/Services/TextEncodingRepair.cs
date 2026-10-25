using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// JSON 请求体的编码容错。
/// 客户端可能按系统代码页（GBK/GB18030）发送字节，或把 UTF-8 字节当成 Latin-1
/// 二次编码（ä¸­æ–‡ 这类典型乱码）。这里统一还原，避免中文在手机悬浮条等位置显示成豆腐块。
/// </summary>
public static class TextEncodingRepair {
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public static async Task<JsonObject?> ReadJsonObjectAsync(HttpRequest request, CancellationToken ct) {
        request.EnableBuffering();
        using var buffer = new MemoryStream();
        await request.Body.CopyToAsync(buffer, ct);
        if (request.Body.CanSeek) request.Body.Position = 0;
        var bytes = buffer.ToArray();
        if (bytes.Length == 0) return null;
        var text = RepairText(Decode(bytes, Charset(request.ContentType)));
        var node = JsonNode.Parse(text);
        if (node is JsonObject obj) { RepairTree(obj); return obj; }
        return null;
    }

    internal static string? Charset(string? contentType) {
        if (string.IsNullOrEmpty(contentType)) return null;
        foreach (var part in contentType.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)) {
            if (part.StartsWith("charset=", StringComparison.OrdinalIgnoreCase))
                return part[8..].Trim('"', '\'');
        }
        return null;
    }

    /// 显式 charset 优先；否则严格 UTF-8；失败再按 GB18030（兼容 GBK/GB2312）回退。
    public static string Decode(byte[] bytes, string? charset) {
        if (!string.IsNullOrEmpty(charset) && !charset.Equals("utf-8", StringComparison.OrdinalIgnoreCase)) {
            try { return Encoding.GetEncoding(charset).GetString(bytes); } catch { }
        }
        try { return StrictUtf8.GetString(bytes); }
        catch (DecoderFallbackException) { }
        foreach (var name in new[] { 54936, 936 }) {
            try { return Encoding.GetEncoding(name).GetString(bytes); } catch { }
        }
        return Encoding.Latin1.GetString(bytes);
    }

    /// 修复「UTF-8 字节被当作 Latin-1/CP1252 再编码」的双重编码文本；正常文本原样返回。
    /// 整串乱码直接还原；混合文本（正常中文 + 局部乱码）逐段还原。
    public static string RepairText(string text) {
        if (string.IsNullOrEmpty(text)) return text;
        if (IsLatin1Only(text)) return TryUtf8(Encoding.Latin1.GetBytes(text)) ?? text;
        return MojibakeRun.Replace(text, match => TryUtf8(Encoding.Latin1.GetBytes(match.Value)) ?? match.Value);
    }

    private static readonly System.Text.RegularExpressions.Regex MojibakeRun =
        new(@"[\u0080-\u00FF]{2,}", System.Text.RegularExpressions.RegexOptions.Compiled);

    private static string? TryUtf8(byte[] bytes) {
        try {
            var candidate = StrictUtf8.GetString(bytes);
            return candidate.Length > 0 && candidate.Any(c => c > 0xFF) ? candidate : null;
        } catch (DecoderFallbackException) { return null; }
    }

    private static bool IsLatin1Only(string text) {
        foreach (var c in text) if (c > 0xFF) return false;
        return true;
    }

    private static void RepairTree(JsonNode? node) {
        switch (node) {
            case JsonObject obj:
                foreach (var key in obj.Select(p => p.Key).ToList()) {
                    if (obj[key] is JsonValue value && value.TryGetValue<string>(out var text)) {
                        var fixedText = RepairText(text);
                        if (!ReferenceEquals(fixedText, text) && fixedText != text) obj[key] = fixedText;
                    } else if (obj[key] is JsonNode child) RepairTree(child);
                }
                break;
            case JsonArray array:
                for (var i = 0; i < array.Count; i++) {
                    if (array[i] is JsonValue value && value.TryGetValue<string>(out var text)) {
                        var fixedText = RepairText(text);
                        if (fixedText != text) array[i] = fixedText;
                    } else RepairTree(array[i]);
                }
                break;
        }
    }
}
