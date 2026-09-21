namespace T4L.Api.Diagnostics;

public sealed record ClientDiagnosticEvent(
    DateTimeOffset Timestamp,
    string Level,
    string EventName,
    IReadOnlyDictionary<string, string>? Attributes,
    Guid ClientId,
    string AppVersion);

public sealed record ClientDiagnosticBatch(IReadOnlyList<ClientDiagnosticEvent> Events);

public sealed partial class ClientDiagnosticSink(ILogger<ClientDiagnosticSink> logger)
{
    private static readonly HashSet<string> AllowedAttributes =
        ["durationMs", "outcome", "errorType", "pendingCount", "conflictCount"];

    public void Write(ClientDiagnosticEvent item)
    {
        if (string.IsNullOrWhiteSpace(item.EventName) || item.EventName.Length > 80 || item.AppVersion.Length > 40)
            throw new ArgumentException("invalid_diagnostic_event");
        var safeAttributes = item.Attributes?.Where(x => AllowedAttributes.Contains(x.Key))
            .ToDictionary(x => x.Key, x => new string(x.Value.Take(120).ToArray())) ?? [];
        LogClientEvent(logger, item.EventName, item.Level, item.ClientId, item.AppVersion, safeAttributes);
    }

    [LoggerMessage(EventId = 3001, Level = LogLevel.Information,
        Message = "Client diagnostic {EventName} level={DiagnosticLevel} client={ClientId} appVersion={AppVersion} attributes={Attributes}")]
    private static partial void LogClientEvent(ILogger logger, string eventName, string diagnosticLevel, Guid clientId, string appVersion, IReadOnlyDictionary<string, string> attributes);
}
