namespace RemoteTool.WebApi.Services;

public sealed class HermesSetupRequired(string message) : Exception(message);
