using System.Diagnostics;
using Microsoft.AspNetCore.Routing;

namespace T4L.Api.Diagnostics;

public sealed class DiagnosticLoggingOptions
{
    public bool Enabled { get; init; }
    public string Level { get; init; } = "Basic";
}

public sealed partial class DiagnosticLoggingMiddleware(RequestDelegate next, DiagnosticLoggingOptions options)
{
    public async Task InvokeAsync(HttpContext context, ILogger<DiagnosticLoggingMiddleware> logger)
    {
        if (!options.Enabled)
        {
            await next(context);
            return;
        }

        var stopwatch = Stopwatch.StartNew();
        await next(context);
        stopwatch.Stop();
        if (!logger.IsEnabled(LogLevel.Information))
        {
            return;
        }

        var route = (context.GetEndpoint() as RouteEndpoint)?.RoutePattern.RawText
            ?? context.Request.Path.Value
            ?? "unknown";
        if (options.Level.Equals("Verbose", StringComparison.OrdinalIgnoreCase))
        {
            LogVerboseRequest(
                logger,
                context.Request.Method,
                route,
                context.Response.StatusCode,
                stopwatch.ElapsedMilliseconds,
                context.Request.ContentType ?? "none",
                context.Request.ContentLength,
                context.Request.QueryString.HasValue,
                context.Request.Headers.Authorization.Count > 0);
            return;
        }

        LogBasicRequest(logger, context.Request.Method, route, context.Response.StatusCode, stopwatch.ElapsedMilliseconds);
    }

    [LoggerMessage(
        EventId = 1001,
        Level = LogLevel.Information,
        Message = "Diagnostic request {Method} {Route} returned {StatusCode} in {ElapsedMs} ms at Basic")]
    private static partial void LogBasicRequest(
        ILogger logger,
        string method,
        string route,
        int statusCode,
        long elapsedMs);

    [LoggerMessage(
        EventId = 1002,
        Level = LogLevel.Information,
        Message = "Diagnostic request {Method} {Route} returned {StatusCode} in {ElapsedMs} ms at Verbose; ContentType={ContentType} ContentLength={ContentLength} HasQuery={HasQuery} HasAuthorization={HasAuthorization}")]
    private static partial void LogVerboseRequest(
        ILogger logger,
        string method,
        string route,
        int statusCode,
        long elapsedMs,
        string contentType,
        long? contentLength,
        bool hasQuery,
        bool hasAuthorization);
}
