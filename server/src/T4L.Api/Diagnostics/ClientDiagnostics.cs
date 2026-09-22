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
    private static readonly HashSet<string> AllowedErrorTypes =
        ["IOException", "ConnectException", "UnknownHostException", "SocketTimeoutException", "IllegalArgumentException", "IllegalStateException", "SerializationException", "AEADBadTagException", "SecurityException", "Other"];
    private static readonly HashSet<string> AllowedEventNames =
    [
        "application_started", "diagnostic_level_changed", "sync_started", "sync_succeeded", "sync_retry",
        "ui_action_failed", "backup_export_succeeded", "backup_export_failed", "backup_import_succeeded",
        "backup_import_failed", "sync_conflict_resolution_queued", "pomodoro_notification_denied", "profile_actor_rebound"
    ];
    private static readonly HashSet<string> AllowedAttributes =
        ["durationMs", "outcome", "errorType", "pendingCount", "conflictCount", "level", "phase", "operation", "status"];

    public void Write(ClientDiagnosticEvent item)
    {
        if (!AllowedEventNames.Contains(item.EventName) || item.Level is not ("basic" or "verbose") ||
            string.IsNullOrWhiteSpace(item.AppVersion) || item.AppVersion.Length > 40 ||
            item.AppVersion.Any(char.IsControl) ||
            item.Attributes?.Keys.Any(key => !AllowedAttributes.Contains(key)) == true)
            throw new ArgumentException("invalid_diagnostic_event");
        var safeAttributes = item.Attributes?.Where(x => AllowedAttributes.Contains(x.Key))
            .ToDictionary(x => x.Key, x => ValidateValue(x.Key, x.Value)) ?? [];
        LogClientEvent(logger, item.EventName, item.Level, item.ClientId, item.AppVersion, safeAttributes);
    }

    private static string ValidateValue(string key, string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 120 || value.Any(char.IsControl))
            throw new ArgumentException("invalid_diagnostic_attribute");
        var valid = key switch
        {
            "durationMs" or "pendingCount" or "conflictCount" => long.TryParse(value, out var number) && number >= 0,
            "level" => value is "OFF" or "BASIC" or "VERBOSE",
            "phase" => value is "sync",
            "outcome" => value is "success" or "failure" or "denied" or "queued",
            "errorType" => AllowedErrorTypes.Contains(value),
            "operation" or "status" => value.All(x => char.IsLetterOrDigit(x) || x is '_' or '-' or '.'),
            _ => false
        };
        return valid ? value : throw new ArgumentException("invalid_diagnostic_attribute");
    }

    [LoggerMessage(EventId = 3001, Level = LogLevel.Information,
        Message = "Client diagnostic {EventName} level={DiagnosticLevel} client={ClientId} appVersion={AppVersion} attributes={Attributes}")]
    private static partial void LogClientEvent(ILogger logger, string eventName, string diagnosticLevel, Guid clientId, string appVersion, IReadOnlyDictionary<string, string> attributes);
}
