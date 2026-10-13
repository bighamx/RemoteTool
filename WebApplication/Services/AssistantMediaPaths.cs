using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services;

public static class AssistantMediaPaths
{
    public static IEnumerable<string> Read(string text)
    {
        char? fence = null; var length = 0;
        foreach (var line in text.Replace("\r\n", "\n").Split('\n')) {
            var delimiter = Regex.Match(line, "^ {0,3}(`{3,}|~{3,})(.*)$");
            if (fence != null) {
                if (delimiter.Success && delimiter.Groups[1].Value[0] == fence &&
                    delimiter.Groups[1].Length >= length && string.IsNullOrWhiteSpace(delimiter.Groups[2].Value)) fence = null;
                continue;
            }
            if (delimiter.Success) { fence = delimiter.Groups[1].Value[0]; length = delimiter.Groups[1].Length; continue; }
            var marker = Regex.Match(line, "^ {0,3}MEDIA:[ \\t]*(.+?)[ \\t]*$", RegexOptions.IgnoreCase);
            if (!marker.Success) continue;
            var path = marker.Groups[1].Value.Trim().Trim('\"', '\'');
            string absolute = null;
            try { if (Path.IsPathFullyQualified(path)) absolute = Path.GetFullPath(path); }
            catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException) { }
            if (absolute != null) yield return absolute;
        }
    }
}
