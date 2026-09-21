using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;
using OpenTelemetry.Logs;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;
using T4L.Api.Diagnostics;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Sync;
using T4L.Api.Security;

var builder = WebApplication.CreateBuilder(args);
var buildIdentity = BuildIdentity.Load();
builder.Services.AddSingleton(buildIdentity);
var insecure = builder.Configuration.GetValue<bool>("T4L:DevelopmentInsecure");
if (insecure && !builder.Environment.IsDevelopment())
{
    throw new InvalidOperationException("DevelopmentInsecure is allowed only in Development.");
}
if (insecure)
{
    var urls = builder.Configuration["ASPNETCORE_URLS"] ?? string.Empty;
    var loopbackOnly = urls.Split(';', StringSplitOptions.RemoveEmptyEntries)
        .All(x => x.Contains("localhost", StringComparison.OrdinalIgnoreCase) || x.Contains("127.0.0.1", StringComparison.Ordinal) || x.Contains("[::1]", StringComparison.Ordinal));
    if (!loopbackOnly && !builder.Configuration.GetValue<bool>("T4L:AllowInsecureLan"))
        throw new InvalidOperationException("T4L__AllowInsecureLan=true is required to expose DevelopmentInsecure beyond loopback.");
}
if (!insecure)
{
    var authority = builder.Configuration["Oidc:Authority"];
    var audience = builder.Configuration["Oidc:Audience"];
    if (!Uri.TryCreate(authority, UriKind.Absolute, out var authorityUri) || authorityUri.Scheme != Uri.UriSchemeHttps || string.IsNullOrWhiteSpace(audience))
        throw new InvalidOperationException("Oidc__Authority must be HTTPS and Oidc__Audience is required.");
    builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(options =>
    {
        options.Authority = authority;
        options.Audience = audience;
        options.RequireHttpsMetadata = true;
    });
}
builder.Services.AddAuthorization();

var debugOptions = builder.Configuration.GetSection("DebugLogging").Get<DiagnosticLoggingOptions>() ?? new();
if (!new[] { "Basic", "Verbose" }.Contains(debugOptions.Level, StringComparer.OrdinalIgnoreCase))
{
    throw new InvalidOperationException("DebugLogging__Level must be Basic or Verbose.");
}

builder.Services.AddSingleton(debugOptions);
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddDbContext<T4LDbContext>(options =>
    options.UseNpgsql(builder.Configuration.GetConnectionString("T4L")));
builder.Services.AddSignalR();
builder.Services.AddScoped<SyncService>();
builder.Services.AddScoped<WorkspaceTransferService>();
builder.Services.AddHttpContextAccessor();
builder.Services.AddScoped<ICurrentActor, CurrentActor>();
builder.Services.AddScoped<WorkspaceAccess>();
builder.Services.AddScoped<ClientDiagnosticSink>();
builder.Services.ConfigureHttpJsonOptions(options =>
    options.SerializerOptions.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase)));

var otlpEndpoint = builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"];
var requireExternalSink = builder.Configuration.GetValue<bool>("Observability:RequireExternalSink") || builder.Environment.IsProduction();
if (requireExternalSink && !Uri.TryCreate(otlpEndpoint, UriKind.Absolute, out _))
    throw new InvalidOperationException("OTEL_EXPORTER_OTLP_ENDPOINT is required by the active operational profile.");
builder.Services.AddOpenTelemetry()
    .ConfigureResource(resource => resource.AddService("t4l-api"))
    .WithTracing(tracing =>
    {
        tracing.AddAspNetCoreInstrumentation().AddHttpClientInstrumentation();
        if (Uri.TryCreate(otlpEndpoint, UriKind.Absolute, out var endpoint))
        {
            tracing.AddOtlpExporter(options => options.Endpoint = endpoint);
        }
    });
builder.Logging.AddJsonConsole();
builder.Logging.AddOpenTelemetry(options =>
{
    options.IncludeFormattedMessage = true;
    if (Uri.TryCreate(otlpEndpoint, UriKind.Absolute, out var endpoint))
    {
        options.AddOtlpExporter(exporter => exporter.Endpoint = endpoint);
    }
});

var app = builder.Build();
app.UseExceptionHandler(error => error.Run(async context =>
{
    var exception = context.Features.Get<IExceptionHandlerFeature>()?.Error;
    context.Response.StatusCode = exception switch { ArgumentException => 400, UnauthorizedAccessException => 403, _ => 500 };
    await Results.Problem(
        statusCode: context.Response.StatusCode,
        title: context.Response.StatusCode switch { 400 => "Invalid request", 403 => "Forbidden", _ => "Unexpected error" },
        detail: builder.Environment.IsDevelopment() ? exception?.Message : null,
        extensions: new Dictionary<string, object?> { ["correlationId"] = context.TraceIdentifier })
        .ExecuteAsync(context);
}));
app.UseMiddleware<DiagnosticLoggingMiddleware>();
if (!insecure) app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/health/live", () => Results.Ok(new { status = "live" }));
app.MapGet("/about", (BuildIdentity identity) => Results.Ok(identity));
app.MapGet("/health/ready", async (T4LDbContext db, CancellationToken ct) =>
    await db.Database.CanConnectAsync(ct)
        ? Results.Ok(new { status = "ready" })
        : Results.Problem(statusCode: 503, title: "Database unavailable"));

var api = app.MapGroup("/api/v1");
if (!insecure) api.RequireAuthorization();
api.MapGet("/bootstrap", async (ICurrentActor currentActor, T4LDbContext db, CancellationToken ct) =>
{
    var actor = await currentActor.GetAsync(ct);
    var workspaces = await db.Workspaces.AsNoTracking().Where(x => actor.Workspaces.Keys.Contains(x.Id))
        .OrderBy(x => x.Name).Select(x => new { workspaceId = x.Id, x.Name }).ToArrayAsync(ct);
    return Results.Ok(new { userId = actor.UserId, defaultWorkspaceId = workspaces.FirstOrDefault()?.workspaceId, workspaces = workspaces.Select(x => new { x.workspaceId, x.Name, role = actor.Workspaces[x.workspaceId].ToString().ToLowerInvariant() }) });
});
api.MapGet("/workspaces/{workspaceId:guid}/category-trees", async (
    Guid workspaceId,
    T4LDbContext db,
    WorkspaceAccess access,
    CancellationToken ct) =>
{
    await access.RequireAsync(workspaceId, MembershipRole.Viewer, ct);
    return await db.CategoryTrees.AsNoTracking()
        .Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null)
        .OrderBy(x => x.SortOrder).ThenBy(x => x.Name)
        .ToArrayAsync(ct);
});
api.MapGet("/workspaces/{workspaceId:guid}/reports/aggregate", async (
    Guid workspaceId,
    DateTimeOffset from,
    DateTimeOffset to,
    T4LDbContext db,
    WorkspaceAccess access,
    TimeProvider clock,
    CancellationToken ct) =>
{
    await access.RequireAsync(workspaceId, MembershipRole.Viewer, ct);
    if (to <= from) throw new ArgumentException("to must be greater than from.");
    var events = await db.Events.AsNoTracking()
        .Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null && x.OccurredAt < to)
        .ToArrayAsync(ct);
    var categories = await db.Categories.AsNoTracking()
        .Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null)
        .ToArrayAsync(ct);
    return Results.Ok(TimelineEngine.Aggregate(
        TimelineEngine.BuildIntervals(events, from, to, clock.GetUtcNow()), categories));
});
api.MapPost("/workspaces/{workspaceId:guid}/reports/intersection", async (
    Guid workspaceId,
    IntersectionRequest request,
    T4LDbContext db,
    WorkspaceAccess access,
    TimeProvider clock,
    CancellationToken ct) =>
{
    await access.RequireAsync(workspaceId, MembershipRole.Viewer, ct);
    if (request.To <= request.From || request.Filters.Count < 2 || request.Filters.GroupBy(x => x.CategoryTreeId).Any(x => x.Count() > 1)) throw new ArgumentException("Invalid intersection request.");
    var events = await db.Events.AsNoTracking()
        .Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null && x.OccurredAt < request.To)
        .ToArrayAsync(ct);
    var intervals = TimelineEngine.BuildIntervals(events, request.From, request.To, clock.GetUtcNow());
    var filters = request.Filters.ToDictionary(
        x => x.CategoryTreeId,
        x => (IReadOnlySet<Guid?>)x.CategoryIds.ToHashSet());
    return Results.Ok(new { durationSeconds = TimelineEngine.Intersect(intervals, filters) });
});
api.MapPost("/workspaces/{workspaceId:guid}/planner/recommendations", async (
    Guid workspaceId,
    RecommendationRequest request,
    T4LDbContext db,
    WorkspaceAccess access,
    TimeProvider clock,
    CancellationToken ct) =>
{
    await access.RequireAsync(workspaceId, MembershipRole.Viewer, ct);
    if (request.WindowMinutes < 1 || request.PlanningFactor <= 0) throw new ArgumentException("Invalid recommendation request.");
    var tasks = await db.Tasks.AsNoTracking()
        .Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null)
        .ToArrayAsync(ct);
    return Results.Ok(PlanningEngine.Recommend(
        tasks,
        request.WindowMinutes,
        request.Energy,
        clock.GetUtcNow(),
        request.PlanningFactor));
});
api.MapPost("/sync/push", (PushRequest request, SyncService sync, CancellationToken ct) =>
    sync.PushAsync(request, ct));
api.MapGet("/sync/changes", async (Guid workspaceId, long? cursor, int? limit, SyncService sync, WorkspaceAccess access, CancellationToken ct) =>
{
    await access.RequireAsync(workspaceId, MembershipRole.Viewer, ct);
    return await sync.PullAsync(workspaceId, Math.Max(0, cursor ?? 0), Math.Clamp(limit ?? 200, 1, 500), ct);
});
api.MapGet("/sync/snapshot", async (Guid workspaceId, T4LDbContext db, WorkspaceAccess access, CancellationToken ct) =>
{
    await access.RequireAsync(workspaceId, MembershipRole.Viewer, ct);
    var cursor = await db.ChangeFeed.Where(x => x.WorkspaceId == workspaceId)
        .MaxAsync(x => (long?)x.Sequence, ct) ?? 0;
    return Results.Ok(new
    {
        formatVersion = 2,
        exportedAt = DateTimeOffset.UtcNow,
        workspaceId,
        cursor,
        categoryTrees = await db.CategoryTrees.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        categories = await db.Categories.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        events = await db.Events.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        tasks = await db.Tasks.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        taskComments = await db.TaskComments.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        plans = await db.Plans.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        budgetAllocations = await db.BudgetAllocations.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct),
        plannedEvents = await db.PlannedEvents.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && x.DeletedAt == null).ToArrayAsync(ct)
    });
});
api.MapPost("/imports", (WorkspaceImportRequest request, WorkspaceTransferService transfer, CancellationToken ct) =>
    transfer.ImportAsync(request, ct));
api.MapPost("/diagnostics/events", async (ClientDiagnosticBatch batch, ICurrentActor actor, ClientDiagnosticSink sink, CancellationToken ct) =>
{
    _ = await actor.GetAsync(ct);
    if (batch.Events.Count is < 1 or > 100) throw new ArgumentException("A diagnostic batch must contain between 1 and 100 events.");
    foreach (var item in batch.Events) sink.Write(item);
    return Results.Accepted();
});
var hub = app.MapHub<ChangeHub>("/hubs/changes");
if (!insecure) hub.RequireAuthorization();

await using (var scope = app.Services.CreateAsyncScope())
{
    await DatabaseBootstrap.InitializeAsync(scope.ServiceProvider.GetRequiredService<T4LDbContext>(), CancellationToken.None);
}

await app.RunAsync();

public partial class Program;
