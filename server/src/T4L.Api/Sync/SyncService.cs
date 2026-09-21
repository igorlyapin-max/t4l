using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Security;

namespace T4L.Api.Sync;

public sealed partial class SyncService(
    T4LDbContext db,
    IHubContext<ChangeHub> hub,
    WorkspaceAccess workspaceAccess,
    TimeProvider timeProvider,
    ILogger<SyncService> logger)
{
    private static readonly JsonSerializerOptions JsonOptions = CreateJsonOptions();
    public async Task<PushResponse> PushAsync(PushRequest request, CancellationToken cancellationToken)
    {
        if (request.Mutations.Count is < 1 or > 100)
        {
            throw new ArgumentException("A sync batch must contain between 1 and 100 mutations.");
        }

        var results = new List<MutationResult>(request.Mutations.Count);
        foreach (var mutation in request.Mutations)
        {
            results.Add(await ProcessMutationAsync(request.ClientId, mutation, cancellationToken));
        }

        LogPushProcessed(logger, request.Mutations.Count);
        return new PushResponse(results);
    }

    public async Task<ChangePage> PullAsync(Guid workspaceId, long cursor, int limit, CancellationToken cancellationToken)
    {
        var page = await db.ChangeFeed.AsNoTracking()
            .Where(x => x.WorkspaceId == workspaceId && x.Sequence > cursor)
            .OrderBy(x => x.Sequence)
            .Take(limit + 1)
            .ToArrayAsync(cancellationToken);
        var hasMore = page.Length > limit;
        var visible = page.Take(limit).ToArray();
        var changes = visible.Select(x => new ChangeDto(
            x.Sequence,
            x.EntityType,
            x.EntityId,
            x.Revision,
            x.Deleted,
            JsonDocument.Parse(x.PayloadJson).RootElement.Clone())).ToArray();
        return new ChangePage(changes, visible.LastOrDefault()?.Sequence ?? cursor, hasMore);
    }

    private async Task<MutationResult> ApplyAsync(MutationDto mutation, CancellationToken cancellationToken)
    {
        var actor = await workspaceAccess.RequireAsync(mutation.WorkspaceId, MembershipRole.Editor, cancellationToken);
        if (mutation.EntityType.Equals("taskComment", StringComparison.OrdinalIgnoreCase) &&
            mutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase) &&
            RequiredPayload<TaskCommentEntity>(mutation).AuthorId != actor.UserId)
            return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "comment_author_mismatch");

        var validationError = await ValidateMutationAsync(mutation, cancellationToken);
        if (validationError is not null)
        {
            return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: validationError);
        }

        return mutation.EntityType.ToLowerInvariant() switch
        {
            "categorytree" => await ApplyEntityAsync<CategoryTreeEntity>(mutation, cancellationToken),
            "category" => await ApplyEntityAsync<CategoryEntity>(mutation, cancellationToken),
            "event" => await ApplyEntityAsync<TimeEventEntity>(mutation, cancellationToken),
            "task" => await ApplyEntityAsync<TaskEntity>(mutation, cancellationToken),
            "taskcomment" => await ApplyEntityAsync<TaskCommentEntity>(mutation, cancellationToken),
            "plan" => await ApplyEntityAsync<PlanEntity>(mutation, cancellationToken),
            "budgetallocation" => await ApplyEntityAsync<BudgetAllocationEntity>(mutation, cancellationToken),
            "plannedevent" => await ApplyEntityAsync<PlannedEventEntity>(mutation, cancellationToken),
            _ => new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "unknown_entity_type")
        };
    }

    private async Task<string?> ValidateMutationAsync(MutationDto mutation, CancellationToken cancellationToken)
    {
        if (!mutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase)) return null;
        try
        {
            switch (mutation.EntityType.ToLowerInvariant())
            {
                case "category":
                {
                    var item = RequiredPayload<CategoryEntity>(mutation);
                    if (string.IsNullOrWhiteSpace(item.Name) || item.Name.Length > 120) return "invalid_category_name";
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "category_tree_not_found";
                    if (item.ParentId == mutation.EntityId) return "category_cycle";
                    var parent = item.ParentId;
                    var visited = new HashSet<Guid> { mutation.EntityId };
                    while (parent is { } parentId)
                    {
                        if (!visited.Add(parentId)) return "category_cycle";
                        var parentRow = await db.Categories.AsNoTracking().SingleOrDefaultAsync(x => x.Id == parentId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken);
                        if (parentRow is null || parentRow.Archived || parentRow.CategoryTreeId != item.CategoryTreeId) return "invalid_category_parent";
                        parent = parentRow.ParentId;
                    }
                    break;
                }
                case "event":
                {
                    var item = RequiredPayload<TimeEventEntity>(mutation);
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "category_tree_not_found";
                    if (item.CategoryId is { } categoryId && !await db.Categories.AnyAsync(x => x.Id == categoryId && x.WorkspaceId == mutation.WorkspaceId && x.CategoryTreeId == item.CategoryTreeId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "invalid_event_category";
                    if (item.TaskId is { } taskId && await EffectiveCategoryAsync(taskId, mutation.WorkspaceId, cancellationToken) != item.CategoryId) return "event_task_category_mismatch";
                    if (item.OccurredAt > timeProvider.GetUtcNow()) return "factual_event_in_future";
                    if (item.Note?.Length > 2000) return "event_note_too_long";
                    break;
                }
                case "task":
                {
                    var item = RequiredPayload<TaskEntity>(mutation);
                    if (string.IsNullOrWhiteSpace(item.Title) || item.Title.Length > 240 || item.EstimateMinutes < 0 || item.RemainingEstimateMinutes < 0 || item.RemainingEstimateMinutes > item.EstimateMinutes || item.Value is < 0 or > 100 || item.Progress is < 0 or > 100) return "invalid_task";
                    if ((item.CategoryId is null) == (item.ParentTaskId is null)) return "task_requires_exactly_one_parent";
                    if (item.Status == TaskState.Active && item.NextActionDate is null) return "active_task_requires_next_action_date";
                    if (item.CategoryId is { } categoryId && !await db.Categories.AnyAsync(x => x.Id == categoryId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "task_category_not_found";
                    var parent = item.ParentTaskId;
                    var visited = new HashSet<Guid> { mutation.EntityId };
                    while (parent is { } parentId)
                    {
                        if (!visited.Add(parentId)) return "task_cycle";
                        var parentRow = await db.Tasks.AsNoTracking().SingleOrDefaultAsync(x => x.Id == parentId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken);
                        if (parentRow is null) return "task_parent_not_found";
                        parent = parentRow.ParentTaskId;
                    }
                    break;
                }
                case "taskcomment":
                {
                    var item = RequiredPayload<TaskCommentEntity>(mutation);
                    if (string.IsNullOrWhiteSpace(item.Text) || item.Text.Length > 4000 || !await db.Tasks.AnyAsync(x => x.Id == item.TaskId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken)) return "invalid_task_comment";
                    break;
                }
                case "plan":
                {
                    var item = RequiredPayload<PlanEntity>(mutation);
                    if (string.IsNullOrWhiteSpace(item.Name) || item.Name.Length > 120 || item.EndsAt <= item.StartsAt) return "invalid_plan";
                    break;
                }
                case "budgetallocation":
                {
                    var item = RequiredPayload<BudgetAllocationEntity>(mutation);
                    if (item.OwnMinutes < 0 || !await db.Plans.AnyAsync(x => x.Id == item.PlanId && x.WorkspaceId == mutation.WorkspaceId && x.Kind == PlanKind.Budget && x.DeletedAt == null && !x.Archived, cancellationToken)) return "invalid_budget_allocation";
                    if (!await db.Categories.AnyAsync(x => x.Id == item.CategoryId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "budget_category_not_found";
                    break;
                }
                case "plannedevent":
                {
                    var item = RequiredPayload<PlannedEventEntity>(mutation);
                    var plan = await db.Plans.AsNoTracking().SingleOrDefaultAsync(x => x.Id == item.PlanId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken);
                    if (plan is null || plan.Kind != PlanKind.Timeline || item.OccurredAt < plan.StartsAt || item.OccurredAt >= plan.EndsAt) return "planned_event_outside_plan";
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "category_tree_not_found";
                    if (item.CategoryId is { } categoryId && !await db.Categories.AnyAsync(x => x.Id == categoryId && x.WorkspaceId == mutation.WorkspaceId && x.CategoryTreeId == item.CategoryTreeId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "invalid_planned_category";
                    if (item.TaskId is { } taskId && await EffectiveCategoryAsync(taskId, mutation.WorkspaceId, cancellationToken) != item.CategoryId) return "planned_task_category_mismatch";
                    if (item.Note?.Length > 2000) return "planned_event_note_too_long";
                    break;
                }
            }
        }
        catch (JsonException)
        {
            return "invalid_payload";
        }
        return null;
    }

    private static TEntity RequiredPayload<TEntity>(MutationDto mutation) where TEntity : SyncEntity =>
        mutation.Payload.Deserialize<TEntity>(JsonOptions) ?? throw new JsonException("Payload is required.");

    private async Task<Guid?> EffectiveCategoryAsync(Guid taskId, Guid workspaceId, CancellationToken cancellationToken)
    {
        var visited = new HashSet<Guid>(); Guid? current = taskId;
        while (current is { } id && visited.Add(id))
        {
            var task = await db.Tasks.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id && x.WorkspaceId == workspaceId && x.DeletedAt == null, cancellationToken);
            if (task is null) return null;
            if (task.CategoryId is { } categoryId) return categoryId;
            current = task.ParentTaskId;
        }
        return null;
    }

    private async Task<MutationResult> ProcessMutationAsync(
        Guid clientId,
        MutationDto mutation,
        CancellationToken cancellationToken)
    {
        var duplicate = await db.ProcessedMutations.AsNoTracking()
            .SingleOrDefaultAsync(x => x.ClientId == clientId && x.WorkspaceId == mutation.WorkspaceId && x.ClientMutationId == mutation.ClientMutationId, cancellationToken);
        if (duplicate is not null)
        {
            var stored = JsonSerializer.Deserialize<MutationResult>(duplicate.ResultJson, JsonOptions)!;
            return stored with { Duplicate = true };
        }

        var effectiveMutation = Canonicalize(mutation);
        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        try
        {
            var result = await ApplyAsync(effectiveMutation, cancellationToken);
            if (effectiveMutation.EntityId != mutation.EntityId)
            {
                result = result with { CanonicalEntityId = effectiveMutation.EntityId };
            }
            AddProcessedMutation(clientId, mutation, result);
            await db.SaveChangesAsync(cancellationToken);
            await transaction.CommitAsync(cancellationToken);
            await NotifyWorkspaceAsync(effectiveMutation, result, cancellationToken);
            return result;
        }
        catch (DbUpdateException)
        {
            await transaction.RollbackAsync(cancellationToken);
            db.ChangeTracker.Clear();
            var racedDuplicate = await db.ProcessedMutations.AsNoTracking()
                .SingleOrDefaultAsync(x => x.ClientId == clientId && x.WorkspaceId == mutation.WorkspaceId && x.ClientMutationId == mutation.ClientMutationId, cancellationToken);
            if (racedDuplicate is not null)
            {
                var stored = JsonSerializer.Deserialize<MutationResult>(racedDuplicate.ResultJson, JsonOptions)!;
                return stored with { Duplicate = true };
            }
            return await BuildConcurrencyConflictAsync(effectiveMutation, cancellationToken);
        }
    }

    private static MutationDto Canonicalize(MutationDto mutation)
    {
        if (!mutation.EntityType.Equals("budgetAllocation", StringComparison.OrdinalIgnoreCase) ||
            !mutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase)) return mutation;
        var item = RequiredPayload<BudgetAllocationEntity>(mutation);
        return mutation with { EntityId = DeterministicIds.BudgetAllocation(mutation.WorkspaceId, item.PlanId, item.CategoryId) };
    }

    private async Task<MutationResult> BuildConcurrencyConflictAsync(MutationDto mutation, CancellationToken cancellationToken)
    {
        SyncEntity? existing;
        if (mutation.EntityType.Equals("budgetAllocation", StringComparison.OrdinalIgnoreCase))
        {
            var item = RequiredPayload<BudgetAllocationEntity>(mutation);
            existing = await db.BudgetAllocations.AsNoTracking().SingleOrDefaultAsync(
                x => x.WorkspaceId == mutation.WorkspaceId && x.PlanId == item.PlanId && x.CategoryId == item.CategoryId,
                cancellationToken);
        }
        else existing = await LoadEntityAsync(mutation.EntityType, mutation.EntityId, mutation.WorkspaceId, cancellationToken);
        JsonElement? payload = existing is null ? null : JsonSerializer.SerializeToElement(existing, existing.GetType(), JsonOptions);
        return new MutationResult(mutation.ClientMutationId, "conflict", existing?.Revision, payload, "concurrent_update",
            existing?.Id ?? mutation.EntityId, false);
    }

    private async Task<SyncEntity?> LoadEntityAsync(string entityType, Guid entityId, Guid workspaceId, CancellationToken cancellationToken) =>
        entityType.ToLowerInvariant() switch
        {
            "categorytree" => await db.CategoryTrees.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "category" => await db.Categories.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "event" => await db.Events.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "task" => await db.Tasks.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "taskcomment" => await db.TaskComments.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "plan" => await db.Plans.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "budgetallocation" => await db.BudgetAllocations.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "plannedevent" => await db.PlannedEvents.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            _ => null
        };

    private void AddProcessedMutation(Guid clientId, MutationDto mutation, MutationResult result)
    {
        db.ProcessedMutations.Add(new ProcessedMutationEntity
        {
            ClientMutationId = mutation.ClientMutationId,
            ClientId = clientId,
            WorkspaceId = mutation.WorkspaceId,
            ResultJson = JsonSerializer.Serialize(result, JsonOptions),
            ProcessedAt = timeProvider.GetUtcNow()
        });
    }

    private async Task NotifyWorkspaceAsync(
        MutationDto mutation,
        MutationResult result,
        CancellationToken cancellationToken)
    {
        if (result.Status is "applied" or "canonicalized")
        {
            await hub.Clients.Group(mutation.WorkspaceId.ToString("D"))
                .SendAsync("workspaceChanged", mutation.WorkspaceId, result.Revision, cancellationToken);
        }
    }

    private async Task<MutationResult> ApplyEntityAsync<TEntity>(MutationDto mutation, CancellationToken cancellationToken)
        where TEntity : SyncEntity, new()
    {
        var set = db.Set<TEntity>();
        var existing = await set.SingleOrDefaultAsync(x => x.Id == mutation.EntityId, cancellationToken);
        if (existing is not null && existing.WorkspaceId != mutation.WorkspaceId)
        {
            return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "workspace_mismatch");
        }
        if ((existing?.Revision ?? 0) != mutation.BaseRevision)
        {
            return new MutationResult(
                mutation.ClientMutationId,
                "conflict",
                existing?.Revision,
                existing is null ? null : JsonSerializer.SerializeToElement(existing, JsonOptions),
                "stale_revision");
        }

        var now = timeProvider.GetUtcNow();
        var entity = existing ?? new TEntity
        {
            Id = mutation.EntityId,
            WorkspaceId = mutation.WorkspaceId,
            CreatedAt = now
        };

        if (mutation.Operation.Equals("delete", StringComparison.OrdinalIgnoreCase))
        {
            if (existing is null)
            {
                return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "entity_not_found");
            }
            entity.DeletedAt = now;
        }
        else if (mutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase))
        {
            var incoming = mutation.Payload.Deserialize<TEntity>(JsonOptions);
            if (incoming is null)
            {
                return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "invalid_payload");
            }
            CopyMutableProperties(incoming, entity);
            entity.DeletedAt = null;
            if (existing is null) set.Add(entity);
        }
        else
        {
            return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "unknown_operation");
        }

        entity.Revision = mutation.BaseRevision + 1;
        entity.UpdatedAt = now;
        var payload = JsonSerializer.Serialize(entity, JsonOptions);
        db.ChangeFeed.Add(new ChangeFeedEntry
        {
            WorkspaceId = mutation.WorkspaceId,
            EntityType = mutation.EntityType,
            EntityId = entity.Id,
            Revision = entity.Revision,
            Deleted = entity.DeletedAt is not null,
            PayloadJson = payload,
            CreatedAt = now
        });
        await db.SaveChangesAsync(cancellationToken);
        return new MutationResult(
            mutation.ClientMutationId,
            "applied",
            entity.Revision,
            JsonDocument.Parse(payload).RootElement.Clone());
    }

    private static void CopyMutableProperties<TEntity>(TEntity source, TEntity target) where TEntity : SyncEntity
    {
        foreach (var property in typeof(TEntity).GetProperties()
                     .Where(x => x.CanRead && x.CanWrite)
                     .Where(x => x.Name is not nameof(SyncEntity.Id)
                         and not nameof(SyncEntity.WorkspaceId)
                         and not nameof(SyncEntity.Revision)
                         and not nameof(SyncEntity.CreatedAt)
                         and not nameof(SyncEntity.UpdatedAt)
                         and not nameof(SyncEntity.DeletedAt)))
        {
            property.SetValue(target, property.GetValue(source));
        }
    }

    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
        return options;
    }

    [LoggerMessage(
        EventId = 2001,
        Level = LogLevel.Information,
        Message = "Sync push processed {MutationCount} mutations")]
    private static partial void LogPushProcessed(ILogger logger, int mutationCount);

}
