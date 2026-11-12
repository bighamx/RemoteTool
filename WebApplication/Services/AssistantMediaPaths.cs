using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services;

public static class AssistantMediaPaths
{
    private static readonly Regex Fence = new("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static readonly Regex LinkStart = new(@"!?\[(?:\\.|[^\]\\])*\]\(");

    public static IEnumerable<string> Read(string text)
    {
        char? fence = null; var length = 0;
        foreach (var line in text.Replace("\r\n", "\n").Split('\n')) {
            var delimiter = Fence.Match(line);
            if (fence != null) {
                if (delimiter.Success && delimiter.Groups[1].Value[0] == fence &&
                    delimiter.Groups[1].Length >= length && string.IsNullOrWhiteSpace(delimiter.Groups[2].Value)) fence = null;
                continue;
            }
            if (delimiter.Success) { fence = delimiter.Groups[1].Value[0]; length = delimiter.Groups[1].Length; continue; }
            if (line.StartsWith("    ") || line.StartsWith('\t')) continue;
            var marker = Regex.Match(line, "^ {0,3}MEDIA:[ \\t]*(.+?)[ \\t]*$", RegexOptions.IgnoreCase);
            if (marker.Success && AbsolutePath(marker.Groups[1].Value.Trim().Trim('"', '\'')) is { } legacy)
                yield return legacy;
            var visible = WithoutInlineCode(line);
            foreach (Match match in LinkStart.Matches(visible)) {
                if (match.Index > 0 && visible[match.Index - 1] == '\\') continue;
                var start = match.Index + match.Length;
                var destination = Destination(visible, start);
                if (destination != null && AbsolutePath(destination) is { } path) yield return path;
            }
        }
    }

    private static string Destination(string text, int start) {
        while (start < text.Length && char.IsWhiteSpace(text[start])) start++;
        if (start >= text.Length) return null;
        if (text[start] == '<') {
            var end = text.IndexOf('>', start + 1);
            if (end < 0 || !Regex.IsMatch(text[(end + 1)..], "^\\s*(?:[\"'].+?[\"']\\s*)?\\)")) return null;
            return text[(start + 1)..end];
        }
        var depth = 0;
        for (var end = start; end < text.Length; end++) {
            var ch = text[end];
            if (ch == '\\' && end + 1 < text.Length && text[end + 1] is '(' or ')' or '\\') { end++; continue; }
            if (ch == '(') depth++;
            if (ch == ')' && depth-- == 0) return text[start..end];
            if (char.IsWhiteSpace(ch) && depth == 0) {
                return Regex.IsMatch(text[end..], "^\\s+[\"'].+?[\"']\\s*\\)") ? text[start..end] : null;
            }
        }
        return null;
    }

    private static string WithoutInlineCode(string text) {
        var chars = text.ToCharArray();
        for (var at = 0; at < text.Length; at++) {
            if (text[at] != '`' || at > 0 && text[at - 1] == '\\') continue;
            var end = at;
            while (end < text.Length && text[end] == '`') end++;
            var delimiter = text[at..end];
            var close = text.IndexOf(delimiter, end, StringComparison.Ordinal);
            if (close < 0) { at = end - 1; continue; }
            Array.Fill(chars, ' ', at, close + delimiter.Length - at);
            at = close + delimiter.Length - 1;
        }
        return new string(chars);
    }

    private static string AbsolutePath(string value) {
        try {
            if (value.StartsWith("file:", StringComparison.OrdinalIgnoreCase) || value.Contains("://") || value.StartsWith("//") || value.StartsWith(@"\\")) return null;
            value = Regex.Replace(value, @"\\([()])", "$1");
            value = Uri.UnescapeDataString(value);
            value = Regex.Replace(value, @":\d+(?::\d+)?$", "");
            return Path.IsPathFullyQualified(value) ? Path.GetFullPath(value) : null;
        } catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException or UriFormatException) {
            return null;
        }
    }
}
