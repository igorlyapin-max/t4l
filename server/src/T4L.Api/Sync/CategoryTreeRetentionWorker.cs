using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;
using T4L.Api.Persistence;

namespace T4L.Api.Sync;

public sealed partial class CategoryTreeRetentionWorker(
    IServiceScopeFactory scopeFactory,
    TimeProvider clock,
    ILogger<CategoryTreeRetentionWorker> logger) : BackgroundService
{
    private static readonly JsonSerializerOptions JsonOptions = CreateJsonOptions();

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1));
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await PurgeExpiredAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception exception) { LogCleanupFailed(logger, exception); }
            try { if (!await timer.WaitForNextTickAsync(stoppingToken)) break; }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
        }
    }

    private async Task PurgeExpiredAsync(CancellationToken cancellationToken)
    {
        using var scope = scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<T4LDbContext>();
        var now = clock.GetUtcNow();
        var expired = await db.CategoryTrees
            .Where(x => x.Archived && x.DeletedAt == null && x.PurgedAt == null && x.TrashedAt != null && x.TrashedAt <= now.AddDays(-30))
            .OrderBy(x => x.TrashedAt)
            .Take(100)
            .ToArrayAsync(cancellationToken);
        foreach (var tree in expired)
        {
            tree.PurgedAt = now;
            tree.Revision++;
            tree.UpdatedAt = now;
            db.ChangeFeed.Add(new ChangeFeedEntry
            {
                WorkspaceId = tree.WorkspaceId,
                EntityType = "categoryTree",
                EntityId = tree.Id,
                Revision = tree.Revision,
                Deleted = false,
                PayloadJson = JsonSerializer.Serialize(tree, JsonOptions),
                CreatedAt = now
            });
        }
        if (expired.Length == 0) return;
        try
        {
            await db.SaveChangesAsync(cancellationToken);
            LogPurged(logger, expired.Length);
        }
        catch (DbUpdateConcurrencyException)
        {
            LogConcurrentUpdate(logger);
        }
    }

    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
        return options;
    }

    [LoggerMessage(EventId = 2101, Level = LogLevel.Error, Message = "Category tree retention cleanup failed")]
    private static partial void LogCleanupFailed(ILogger logger, Exception exception);

    [LoggerMessage(EventId = 2102, Level = LogLevel.Information, Message = "Purged {Count} expired category trees")]
    private static partial void LogPurged(ILogger logger, int count);

    [LoggerMessage(EventId = 2103, Level = LogLevel.Information, Message = "Category tree retention cleanup will retry after a concurrent update")]
    private static partial void LogConcurrentUpdate(ILogger logger);
}
