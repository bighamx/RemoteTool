namespace ChuckieHelper.WebApi.Models
{
    public class CloudflareSettings
    {
        public string ApiToken { get; set; }
        public string ZoneId { get; set; }
        public string RecordName { get; set; }
        public bool Proxied { get; set; } = false;
        public int Ttl { get; set; } = 1; // 1 means automatic
    }
}
