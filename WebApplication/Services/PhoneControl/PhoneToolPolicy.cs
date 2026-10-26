using System.Text.Json.Nodes;
namespace RemoteTool.WebApi.Services.PhoneControl;

public static class PhoneToolPolicy {
    public static int TimeoutSeconds(string tool, JsonObject args) => tool == "phone_manage_app" && args["action"]?.ToString() == "install" ? 120 : 35;
}
