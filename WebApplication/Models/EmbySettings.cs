namespace ChuckieHelper.WebApi.Models
{
    public class EmbySettings
    {
        public string Url { get; set; } = string.Empty;
        public string Token { get; set; } = string.Empty;
        public List<string> VideoExtensions { get; set; } = new List<string> { ".mp4", ".mkv", ".avi", ".rmvb", ".wmv", ".ts", ".iso", ".mov", ".flv", ".webm" };
    }
}
