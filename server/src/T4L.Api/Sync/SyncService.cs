using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Data;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Security;

namespace T4L.Api.Sync;

public sealed partial class SyncService(
    T4LDbContext db,
    IHubContext<ChangeHub> hub,
    WorkspaceAccess workspaceAccess,
    TimeProvider timeProvider,
    ILogger<SyncService> logger,
    IConfiguration? configuration = null)
{
    private static readonly JsonSerializerOptions JsonOptions = CreateJsonOptions();
    private int MaxMutationsPerPush => configuration?.GetValue<int?>("Sync:MaxMutationsPerPush") ?? 100;
    public async Task<PushResponse> PushAsync(PushRequest request, CancellationToken cancellationToken)
    {
        if (MaxMutationsPerPush is < 1 or > 1000)
            throw new InvalidOperationException("Sync:MaxMutationsPerPush must be between 1 and 1000.");
        if (request.Mutations.Count < 1 || request.Mutations.Count > MaxMutationsPerPush)
        {
            throw new ArgumentException($"A sync batch must contain between 1 and {MaxMutationsPerPush} mutations.");
        }

        var results = new List<MutationResult>(request.Mutations.Count);
        var handledGroups = new HashSet<Guid>();
        foreach (var mutation in request.Mutations)
        {
            if (mutation.AtomicGroupId is not { } groupId)
            {
                results.Add(await ProcessMutationAsync(request.ClientId, mutation, cancellationToken));
                continue;
            }
            if (!handledGroups.Add(groupId)) continue;
            var group = request.Mutations.Where(x => x.AtomicGroupId == groupId).ToArray();
            results.AddRange(await ProcessAtomicGroupAsync(request.ClientId, group, cancellationToken));
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
        var planIds = visible.Where(x => !x.Deleted && x.EntityType.Equals("plan", StringComparison.OrdinalIgnoreCase))
            .Select(x => x.EntityId).Distinct().ToArray();
        var canonicalPlans = await db.Plans.AsNoTracking().Where(x => planIds.Contains(x.Id))
            .ToDictionaryAsync(x => x.Id, cancellationToken);
        var treeIds = visible.Where(x => !x.Deleted && x.EntityType.Equals("categoryTree", StringComparison.OrdinalIgnoreCase))
            .Select(x => x.EntityId).Distinct().ToArray();
        var canonicalTrees = await db.CategoryTrees.AsNoTracking().Where(x => treeIds.Contains(x.Id))
            .ToDictionaryAsync(x => x.Id, cancellationToken);
        var categoryIds = visible.Where(x => !x.Deleted && x.EntityType.Equals("category", StringComparison.OrdinalIgnoreCase))
            .Select(x => x.EntityId).Distinct().ToArray();
        var canonicalCategories = await db.Categories.AsNoTracking().Where(x => categoryIds.Contains(x.Id))
            .ToDictionaryAsync(x => x.Id, cancellationToken);
        var changes = visible.Select(x =>
        {
            var payload = JsonNode.Parse(x.PayloadJson)!.AsObject();
            if (!x.Deleted && x.EntityType.Equals("categoryTree", StringComparison.OrdinalIgnoreCase) &&
                canonicalTrees.TryGetValue(x.EntityId, out var canonicalTree))
            {
                if (!payload.ContainsKey("trashedAt") && canonicalTree.TrashedAt is { } trashedAt)
                    payload["trashedAt"] = trashedAt.ToString("O");
                if (!payload.ContainsKey("purgedAt") && canonicalTree.PurgedAt is { } purgedAt)
                    payload["purgedAt"] = purgedAt.ToString("O");
            }
            if (!x.Deleted && x.EntityType.Equals("category", StringComparison.OrdinalIgnoreCase) &&
                canonicalCategories.TryGetValue(x.EntityId, out var canonicalCategory))
            {
                if (!payload.ContainsKey("trashedAt") && canonicalCategory.TrashedAt is { } categoryTrashedAt)
                    payload["trashedAt"] = categoryTrashedAt.ToString("O");
                if (!payload.ContainsKey("purgedAt") && canonicalCategory.PurgedAt is { } categoryPurgedAt)
                    payload["purgedAt"] = categoryPurgedAt.ToString("O");
            }
            if (!x.Deleted && x.EntityType.Equals("plan", StringComparison.OrdinalIgnoreCase) &&
                (!TryPlanPeriod(payload, out var start, out var end) || end <= start) &&
                canonicalPlans.TryGetValue(x.EntityId, out var canonical) && canonical.EndsAt > canonical.StartsAt)
            {
                payload["startsAt"] = canonical.StartsAt.ToString("O");
                payload["endsAt"] = canonical.EndsAt.ToString("O");
            }
            return new ChangeDto(x.Sequence, x.EntityType, x.EntityId, x.Revision, x.Deleted,
                JsonSerializer.SerializeToElement(payload, JsonOptions));
        }).ToArray();
        return new ChangePage(changes, visible.LastOrDefault()?.Sequence ?? cursor, hasMore);
    }

    private static bool TryPlanPeriod(JsonObject payload, out DateTimeOffset start, out DateTimeOffset end)
    {
        var validStart = DateTimeOffset.TryParse(payload["startsAt"]?.ToString(), out start);
        var validEnd = DateTimeOffset.TryParse(payload["endsAt"]?.ToString(), out end);
        return validStart && validEnd;
    }

    private async Task<MutationResult> ApplyAsync(MutationDto mutation, CancellationToken cancellationToken, bool atomicTaskTransition = false, bool validatedClosure = false, bool atomicCategoryGroup = false)
    {
        var actor = await workspaceAccess.RequireAsync(mutation.WorkspaceId, MembershipRole.Editor, cancellationToken);
        if (mutation.EntityType.Equals("taskComment", StringComparison.OrdinalIgnoreCase) &&
            mutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase) &&
            RequiredPayload<TaskCommentEntity>(mutation).AuthorId != actor.UserId)
            return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "comment_author_mismatch");

        var validationError = validatedClosure ? null : await ValidateMutationAsync(mutation, cancellationToken, atomicTaskTransition, atomicCategoryGroup);
        if (validationError is not null)
        {
            return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: validationError);
        }

        return mutation.EntityType.ToLowerInvariant() switch
        {
            "categorytree" => await ApplyEntityAsync<CategoryTreeEntity>(mutation, cancellationToken),
            "treeappearance" => await ApplyEntityAsync<TreeAppearanceEntity>(mutation, cancellationToken),
            "palette" => await ApplyEntityAsync<PaletteEntity>(mutation, cancellationToken),
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

    private async Task<string?> ValidateMutationAsync(MutationDto mutation, CancellationToken cancellationToken, bool atomicTaskTransition = false, bool atomicCategoryGroup = false)
    {
        if (!mutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase)) return null;
        try
        {
            switch (mutation.EntityType.ToLowerInvariant())
            {
                case "treeappearance":
                {
                    var item = RequiredPayload<TreeAppearanceEntity>(mutation);
                    if (item.CategoryTreeId != mutation.EntityId || !System.Text.RegularExpressions.Regex.IsMatch(item.ColorHex, "^#[0-9A-Fa-f]{6}$")) return "invalid_tree_appearance";
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived && x.PurgedAt == null, cancellationToken)) return "category_tree_not_found";
                    break;
                }
                case "palette":
                {
                    var item = RequiredPayload<PaletteEntity>(mutation);
                    PaletteContents contents;
                    try { contents = PaletteValidation.Parse(item); }
                    catch (ArgumentException exception) { return exception.Message; }
                    var previous = await db.Palettes.AsNoTracking().SingleOrDefaultAsync(x => x.Id == mutation.EntityId && x.WorkspaceId == mutation.WorkspaceId, cancellationToken);
                    var previousIds = new HashSet<Guid>();
                    if (previous is not null)
                    {
                        try { previousIds = PaletteValidation.Parse(previous).CategoryIds; }
                        catch (ArgumentException) { return "invalid_palette"; }
                    }
                    var categoryIds = contents.CategoryIds.ToArray();
                    var existingCategories = await db.Categories.AsNoTracking()
                        .Where(x => categoryIds.Contains(x.Id) && x.WorkspaceId == mutation.WorkspaceId)
                        .Where(x => (previousIds.Contains(x.Id) || x.DeletedAt == null && !x.Archived) &&
                            db.CategoryTrees.Any(tree => tree.Id == x.CategoryTreeId && tree.WorkspaceId == mutation.WorkspaceId &&
                                (previousIds.Contains(x.Id) || tree.DeletedAt == null && !tree.Archived && tree.PurgedAt == null)))
                        .Select(x => x.Id).ToArrayAsync(cancellationToken);
                    if (existingCategories.Length != categoryIds.Length) return "palette_category_not_found";
                    if (!await AreCategoriesUsableAsync(categoryIds.Where(x => !previousIds.Contains(x)), mutation.WorkspaceId, cancellationToken))
                        return "palette_category_not_found";
                    var taskIds = contents.TaskIds.ToArray();
                    var existingTaskIds = await db.Tasks.AsNoTracking()
                        .Where(x => taskIds.Contains(x.Id) && x.WorkspaceId == mutation.WorkspaceId)
                        .Select(x => x.Id).ToArrayAsync(cancellationToken);
                    if (existingTaskIds.Length != taskIds.Length) return "palette_task_not_found";
                    break;
                }
                case "category":
                {
                    var item = RequiredPayload<CategoryEntity>(mutation);
                    if (string.IsNullOrWhiteSpace(item.Name) || item.Name.Length > 120) return "invalid_category_name";
                    if (item.Archived && !atomicCategoryGroup && await db.Categories.AnyAsync(x => x.ParentId == mutation.EntityId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken))
                        return "atomic_group_required";
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived && x.PurgedAt == null, cancellationToken)) return "category_tree_not_found";
                    if (item.ParentId == mutation.EntityId) return "category_cycle";
                    var parent = item.ParentId;
                    var visited = new HashSet<Guid> { mutation.EntityId };
                    while (parent is { } parentId)
                    {
                        if (!visited.Add(parentId)) return "category_cycle";
                        var parentRow = await db.Categories.AsNoTracking().SingleOrDefaultAsync(x => x.Id == parentId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken);
                        if (parentRow is null || (!item.Archived && parentRow.Archived) || parentRow.CategoryTreeId != item.CategoryTreeId) return "invalid_category_parent";
                        parent = parentRow.ParentId;
                    }
                    break;
                }
                case "event":
                {
                    var item = RequiredPayload<TimeEventEntity>(mutation);
                    var previous = await db.Events.AsNoTracking().SingleOrDefaultAsync(x => x.Id == mutation.EntityId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken);
                    var historicalTarget = previous is not null && previous.CategoryTreeId == item.CategoryTreeId && previous.CategoryId == item.CategoryId;
                    var activeTargetRequired = !historicalTarget || item.TaskId is not null && item.TaskId != previous!.TaskId;
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && (!activeTargetRequired || !x.Archived && x.PurgedAt == null), cancellationToken)) return "category_tree_not_found";
                    if (item.CategoryId is { } categoryId && !await db.Categories.AnyAsync(x => x.Id == categoryId && x.WorkspaceId == mutation.WorkspaceId && x.CategoryTreeId == item.CategoryTreeId && x.DeletedAt == null && (!activeTargetRequired || !x.Archived), cancellationToken)) return "invalid_event_category";
                    if (activeTargetRequired && item.CategoryId is { } activeEventCategory && !await IsCategoryUsableAsync(activeEventCategory, mutation.WorkspaceId, cancellationToken)) return "invalid_event_category";
                    if (item.TaskId is { } taskId && await EffectiveCategoryAsync(taskId, mutation.WorkspaceId, cancellationToken) != item.CategoryId) return "event_task_category_mismatch";
                    if (item.OccurredAt > timeProvider.GetUtcNow()) return "factual_event_in_future";
                    if (item.Note?.Length > 2000) return "event_note_too_long";
                    break;
                }
                case "task":
                {
                    var item = RequiredPayload<TaskEntity>(mutation);
                    if (mutation.Payload.TryGetProperty("remainingEstimateMinutes", out _)) return "unsupported_task_contract";
                    if (!mutation.Payload.TryGetProperty("sortOrder", out _) || string.IsNullOrWhiteSpace(item.Title) || item.Title.Length > 240 || item.EstimateMinutes < 0 || item.Value is < 0 or > 100 || item.Progress is < 0 or > 100) return "invalid_task";
                    if ((item.CategoryId is null) == (item.ParentTaskId is null)) return "task_requires_exactly_one_parent";
                    if (item.Status == TaskState.Active && item.NextActionDate is null) return "active_task_requires_next_action_date";
                    var existingTask = await db.Tasks.AsNoTracking().SingleOrDefaultAsync(x => x.Id == mutation.EntityId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken);
                    if (existingTask?.Status == TaskState.Active && item.Status != TaskState.Active && !atomicTaskTransition)
                        return "atomic_group_required";
                    if (item.CategoryId is { } categoryId)
                    {
                        var unchangedCategory = existingTask?.CategoryId == categoryId;
                        if (!await db.Categories.AnyAsync(x => x.Id == categoryId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && (unchangedCategory || !x.Archived) && db.CategoryTrees.Any(tree => tree.Id == x.CategoryTreeId && tree.WorkspaceId == mutation.WorkspaceId && tree.DeletedAt == null && (unchangedCategory || !tree.Archived && tree.PurgedAt == null)), cancellationToken)) return "task_category_not_found";
                        if (!unchangedCategory && !await IsCategoryUsableAsync(categoryId, mutation.WorkspaceId, cancellationToken)) return "task_category_not_found";
                    }
                    if (item.ParentTaskId is { } newParentId && existingTask?.ParentTaskId != newParentId)
                    {
                        var effectiveId = await EffectiveCategoryAsync(newParentId, mutation.WorkspaceId, cancellationToken);
                        if (effectiveId is null || !await db.Categories.AnyAsync(x => x.Id == effectiveId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived && db.CategoryTrees.Any(tree => tree.Id == x.CategoryTreeId && tree.WorkspaceId == mutation.WorkspaceId && tree.DeletedAt == null && !tree.Archived && tree.PurgedAt == null), cancellationToken)) return "task_category_not_found";
                        if (!await IsCategoryUsableAsync(effectiveId.Value, mutation.WorkspaceId, cancellationToken)) return "task_category_not_found";
                    }
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
                    if (mutation.Payload.TryGetProperty("kind", out _)) return "unsupported_plan_contract";
                    if (string.IsNullOrWhiteSpace(item.Name) || item.Name.Length > 120 || item.EndsAt <= item.StartsAt) return "invalid_plan";
                    break;
                }
                case "budgetallocation":
                {
                    var item = RequiredPayload<BudgetAllocationEntity>(mutation);
                    if (item.OwnMinutes < 0 || !await db.Plans.AnyAsync(x => x.Id == item.PlanId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken)) return "invalid_budget_allocation";
                    if (!await db.Categories.AnyAsync(x => x.Id == item.CategoryId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived && db.CategoryTrees.Any(tree => tree.Id == x.CategoryTreeId && tree.WorkspaceId == mutation.WorkspaceId && tree.DeletedAt == null && !tree.Archived && tree.PurgedAt == null), cancellationToken)) return "budget_category_not_found";
                    if (!await IsCategoryUsableAsync(item.CategoryId, mutation.WorkspaceId, cancellationToken)) return "budget_category_not_found";
                    break;
                }
                case "plannedevent":
                {
                    var item = RequiredPayload<PlannedEventEntity>(mutation);
                    var plan = await db.Plans.AsNoTracking().SingleOrDefaultAsync(x => x.Id == item.PlanId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && !x.Archived, cancellationToken);
                    if (plan is null || item.OccurredAt < plan.StartsAt || item.OccurredAt >= plan.EndsAt) return "planned_event_outside_plan";
                    var previous = await db.PlannedEvents.AsNoTracking().SingleOrDefaultAsync(x => x.Id == mutation.EntityId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null, cancellationToken);
                    var historicalTarget = previous is not null && previous.CategoryTreeId == item.CategoryTreeId && previous.CategoryId == item.CategoryId;
                    var activeTargetRequired = !historicalTarget || item.TaskId is not null && item.TaskId != previous!.TaskId;
                    if (!await db.CategoryTrees.AnyAsync(x => x.Id == item.CategoryTreeId && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null && (!activeTargetRequired || !x.Archived && x.PurgedAt == null), cancellationToken)) return "category_tree_not_found";
                    if (item.CategoryId is { } plannedCategoryId && !await db.Categories.AnyAsync(x => x.Id == plannedCategoryId && x.WorkspaceId == mutation.WorkspaceId && x.CategoryTreeId == item.CategoryTreeId && x.DeletedAt == null && (!activeTargetRequired || !x.Archived), cancellationToken)) return "invalid_planned_category";
                    if (activeTargetRequired && item.CategoryId is { } activePlannedCategory && !await IsCategoryUsableAsync(activePlannedCategory, mutation.WorkspaceId, cancellationToken)) return "invalid_planned_category";
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

    private async Task<bool> IsCategoryUsableAsync(Guid categoryId, Guid workspaceId, CancellationToken ct)
    {
        var visited = new HashSet<Guid>();
        Guid? cursor = categoryId;
        Guid? treeId = null;
        while (cursor is { } id)
        {
            if (!visited.Add(id)) return false;
            var row = await db.Categories.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id && x.WorkspaceId == workspaceId && x.DeletedAt == null, ct);
            if (row is null || row.Archived || row.PurgedAt is not null || treeId is not null && row.CategoryTreeId != treeId) return false;
            treeId = row.CategoryTreeId;
            cursor = row.ParentId;
        }
        return treeId is { } tid && await db.CategoryTrees.AnyAsync(x => x.Id == tid && x.WorkspaceId == workspaceId && x.DeletedAt == null && !x.Archived && x.PurgedAt == null, ct);
    }

    private async Task<bool> AreCategoriesUsableAsync(IEnumerable<Guid> categoryIds, Guid workspaceId, CancellationToken ct)
    {
        var roots = categoryIds.ToHashSet();
        if (roots.Count == 0) return true;
        var loaded = new Dictionary<Guid, CategoryEntity>();
        var frontier = roots;
        while (frontier.Count > 0)
        {
            var batch = await db.Categories.AsNoTracking().Where(x => x.WorkspaceId == workspaceId && frontier.Contains(x.Id) && x.DeletedAt == null).ToArrayAsync(ct);
            if (batch.Length != frontier.Count || batch.Any(x => x.Archived || x.PurgedAt is not null)) return false;
            foreach (var row in batch) loaded[row.Id] = row;
            frontier = batch.Where(x => x.ParentId is not null).Select(x => x.ParentId!.Value)
                .Where(x => !loaded.ContainsKey(x)).ToHashSet();
        }
        foreach (var id in roots)
        {
            var visited = new HashSet<Guid>();
            var current = loaded[id];
            while (current.ParentId is { } parentId)
            {
                if (!visited.Add(current.Id) || !loaded.TryGetValue(parentId, out var parent) || parent.CategoryTreeId != current.CategoryTreeId) return false;
                current = parent;
            }
        }
        var treeIds = loaded.Values.Select(x => x.CategoryTreeId).Distinct().ToArray();
        return await db.CategoryTrees.AsNoTracking().CountAsync(x => treeIds.Contains(x.Id) && x.WorkspaceId == workspaceId && x.DeletedAt == null && !x.Archived && x.PurgedAt == null, ct) == treeIds.Length;
    }

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

    private async Task<IReadOnlyList<MutationResult>> ProcessAtomicGroupAsync(Guid clientId, MutationDto[] group, CancellationToken ct)
    {
        if (group.All(x => x.EntityType.Equals("category", StringComparison.OrdinalIgnoreCase)))
            return await ProcessCategoryGroupAsync(clientId, group, ct);
        var ordered = group.OrderBy(x => x.EntityType.Equals("task", StringComparison.OrdinalIgnoreCase) ? 0 : 1).ToArray();
        var taskMutation = ordered.FirstOrDefault();
        if (group.Length is < 1 or > 2 || taskMutation is null || !taskMutation.EntityType.Equals("task", StringComparison.OrdinalIgnoreCase) ||
            !taskMutation.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase) ||
            group.Select(x => x.ClientMutationId).Distinct().Count() != group.Length ||
            group.Any(x => x.WorkspaceId != taskMutation.WorkspaceId) ||
            group.Length == 2 && (!ordered[1].EntityType.Equals("event", StringComparison.OrdinalIgnoreCase) || !ordered[1].Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase)))
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_atomic_group")).ToArray();

        var stored = await db.ProcessedMutations.AsNoTracking()
            .Where(x => x.ClientId == clientId && x.WorkspaceId == taskMutation.WorkspaceId && group.Select(m => m.ClientMutationId).Contains(x.ClientMutationId))
            .ToArrayAsync(ct);
        if (stored.Length == group.Length)
            return group.Select(x => JsonSerializer.Deserialize<MutationResult>(stored.Single(s => s.ClientMutationId == x.ClientMutationId).ResultJson, JsonOptions)! with { Duplicate = true }).ToArray();
        if (stored.Length != 0)
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "partial_atomic_group_retry")).ToArray();

        await workspaceAccess.RequireAsync(taskMutation.WorkspaceId, MembershipRole.Editor, ct);
        TaskEntity nextTask;
        try { nextTask = RequiredPayload<TaskEntity>(taskMutation); }
        catch (JsonException) { return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_payload")).ToArray(); }
        var currentTask = await db.Tasks.AsNoTracking().SingleOrDefaultAsync(x => x.Id == taskMutation.EntityId && x.WorkspaceId == taskMutation.WorkspaceId && x.DeletedAt == null, ct);
        if (currentTask?.Status != TaskState.Active || nextTask.Status == TaskState.Active)
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_task_transition")).ToArray();
        var effectiveCategory = await EffectiveCategoryAsync(currentTask.Id, taskMutation.WorkspaceId, ct);
        var category = effectiveCategory is { } categoryId
            ? await db.Categories.AsNoTracking().SingleOrDefaultAsync(x => x.Id == categoryId && x.WorkspaceId == taskMutation.WorkspaceId && x.DeletedAt == null, ct)
            : null;
        var latest = category is null ? null : await db.Events.AsNoTracking()
            .Where(x => x.WorkspaceId == taskMutation.WorkspaceId && x.CategoryTreeId == category.CategoryTreeId && x.DeletedAt == null)
            .OrderByDescending(x => x.OccurredAt).ThenByDescending(x => x.Id).FirstOrDefaultAsync(ct);
        var needsClosure = latest?.TaskId == currentTask.Id;
        if (needsClosure != (group.Length == 2))
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "task_closure_mismatch")).ToArray();
        if (needsClosure)
        {
            TimeEventEntity closure;
            try { closure = RequiredPayload<TimeEventEntity>(ordered[1]); }
            catch (JsonException) { return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_payload")).ToArray(); }
            if (closure.CategoryTreeId != category!.CategoryTreeId || closure.CategoryId != category.Id || closure.TaskId is not null ||
                closure.OccurredAt < latest!.OccurredAt || closure.OccurredAt > timeProvider.GetUtcNow() ||
                closure.Note?.Length > 2000 || await db.Events.AnyAsync(x => x.Id == ordered[1].EntityId, ct))
                return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_task_closure")).ToArray();
        }

        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        try
        {
            var applied = new List<MutationResult>(ordered.Length);
            foreach (var mutation in ordered)
            {
                var result = await ApplyAsync(mutation, ct, atomicTaskTransition: true, validatedClosure: mutation != taskMutation);
                if (result.Status != "applied")
                {
                    await transaction.RollbackAsync(ct);
                    db.ChangeTracker.Clear();
                    return group.Select(x => x.ClientMutationId == result.ClientMutationId ? result : new MutationResult(x.ClientMutationId, result.Status, ErrorCode: "atomic_group_aborted")).ToArray();
                }
                applied.Add(result);
                AddProcessedMutation(clientId, mutation, result);
            }
            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            foreach (var mutation in ordered)
                await NotifyWorkspaceAsync(mutation, applied.Single(x => x.ClientMutationId == mutation.ClientMutationId), ct);
            return group.Select(x => applied.Single(y => y.ClientMutationId == x.ClientMutationId)).ToArray();
        }
        catch (DbUpdateException)
        {
            await transaction.RollbackAsync(ct);
            db.ChangeTracker.Clear();
            return group.Select(x => new MutationResult(x.ClientMutationId, "conflict", ErrorCode: "concurrent_update")).ToArray();
        }
    }

    private async Task<IReadOnlyList<MutationResult>> ProcessCategoryGroupAsync(Guid clientId, MutationDto[] group, CancellationToken ct)
    {
        if (group.Length < 1 || group.Length > MaxMutationsPerPush || group.Select(x => x.ClientMutationId).Distinct().Count() != group.Length ||
            group.Select(x => x.EntityId).Distinct().Count() != group.Length ||
            group.Any(x => x.WorkspaceId != group[0].WorkspaceId || !x.Operation.Equals("upsert", StringComparison.OrdinalIgnoreCase)))
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_atomic_group")).ToArray();
        var ids = group.Select(x => x.ClientMutationId).ToArray();
        var stored = await db.ProcessedMutations.AsNoTracking()
            .Where(x => x.ClientId == clientId && x.WorkspaceId == group[0].WorkspaceId && ids.Contains(x.ClientMutationId))
            .ToArrayAsync(ct);
        if (stored.Length == group.Length)
            return group.Select(x => JsonSerializer.Deserialize<MutationResult>(stored.Single(s => s.ClientMutationId == x.ClientMutationId).ResultJson, JsonOptions)! with { Duplicate = true }).ToArray();
        if (stored.Length != 0)
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "partial_atomic_group_retry")).ToArray();

        await workspaceAccess.RequireAsync(group[0].WorkspaceId, MembershipRole.Editor, ct);
        await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable, ct);
        CategoryEntity[] requested;
        try { requested = group.Select(RequiredPayload<CategoryEntity>).ToArray(); }
        catch (JsonException) { return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_payload")).ToArray(); }
        if (requested.Select(x => x.Archived).Distinct().Count() != 1 ||
            requested.Select(x => x.CategoryTreeId).Distinct().Count() != 1)
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_category_group")).ToArray();
        var archiving = requested[0].Archived;
        if (archiving)
        {
            var groupIds = group.Select(x => x.EntityId).ToHashSet();
            var current = await db.Categories.AsNoTracking().Where(x => x.WorkspaceId == group[0].WorkspaceId && x.DeletedAt == null).ToArrayAsync(ct);
            // Every server-side descendant of an archived node must be included in this transaction.
            var descendants = current.Where(x => x.ParentId is { } parent && groupIds.Contains(parent)).Select(x => x.Id).ToArray();
            if (descendants.Any(x => !groupIds.Contains(x)) ||
                requested.Where(x => x.ParentId is { } parent && groupIds.Contains(parent)).Count() != group.Length - 1)
                return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "incomplete_category_archive_group")).ToArray();
        }
        try
        {
            var applied = new List<MutationResult>(group.Length);
            foreach (var mutation in group)
            {
                var category = RequiredPayload<CategoryEntity>(mutation);
                if (category.Archived != archiving)
                {
                    await transaction.RollbackAsync(ct); db.ChangeTracker.Clear();
                    return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_category_group")).ToArray();
                }
                var result = await ApplyAsync(mutation, ct, atomicCategoryGroup: true);
                if (result.Status != "applied")
                {
                    await transaction.RollbackAsync(ct); db.ChangeTracker.Clear();
                    return group.Select(x => x.ClientMutationId == result.ClientMutationId ? result :
                        new MutationResult(x.ClientMutationId, result.Status, ErrorCode: "atomic_group_aborted")).ToArray();
                }
                AddProcessedMutation(clientId, mutation, result);
                await db.SaveChangesAsync(ct);
                applied.Add(result);
            }
            await transaction.CommitAsync(ct);
            foreach (var (mutation, result) in group.Zip(applied)) await NotifyWorkspaceAsync(mutation, result, ct);
            return applied;
        }
        catch (DbUpdateException)
        {
            await transaction.RollbackAsync(ct); db.ChangeTracker.Clear();
            return group.Select(x => new MutationResult(x.ClientMutationId, "conflict", ErrorCode: "category_group_race")).ToArray();
        }
        catch (PostgresException exception) when (exception.SqlState == PostgresErrorCodes.SerializationFailure)
        {
            await transaction.RollbackAsync(ct); db.ChangeTracker.Clear();
            return group.Select(x => new MutationResult(x.ClientMutationId, "conflict", ErrorCode: "category_group_race")).ToArray();
        }
        catch (JsonException)
        {
            await transaction.RollbackAsync(ct); db.ChangeTracker.Clear();
            return group.Select(x => new MutationResult(x.ClientMutationId, "rejected", ErrorCode: "invalid_payload")).ToArray();
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
            "treeappearance" => await db.TreeAppearances.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
            "palette" => await db.Palettes.AsNoTracking().SingleOrDefaultAsync(x => x.Id == entityId && x.WorkspaceId == workspaceId, cancellationToken),
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

        if (mutation.Operation.Equals("purge", StringComparison.OrdinalIgnoreCase) && entity is CategoryTreeEntity purgeTree)
        {
            if (existing is null || !purgeTree.Archived || purgeTree.PurgedAt is not null)
                return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "category_tree_not_in_trash");
            purgeTree.PurgedAt = now;
        }
        else if (mutation.Operation.Equals("purge", StringComparison.OrdinalIgnoreCase) && entity is PaletteEntity purgePalette)
        {
            if (existing is null || !purgePalette.Archived || purgePalette.PurgedAt is not null)
                return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "palette_not_in_trash");
            purgePalette.PurgedAt = now;
        }
        else if (mutation.Operation.Equals("delete", StringComparison.OrdinalIgnoreCase))
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
            var priorTree = existing as CategoryTreeEntity;
            var wasArchived = priorTree?.Archived == true;
            var previousTrashedAt = priorTree?.TrashedAt;
            var previousPurgedAt = priorTree?.PurgedAt;
            var priorPalette = existing as PaletteEntity;
            var paletteWasArchived = priorPalette?.Archived == true;
            var previousPaletteTrashedAt = priorPalette?.TrashedAt;
            var previousPalettePurgedAt = priorPalette?.PurgedAt;
            var priorCategory = existing as CategoryEntity;
            if (incoming is CategoryTreeEntity incomingTree)
            {
                if (previousPurgedAt is not null)
                    return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "category_tree_purged");
                if (wasArchived && !incomingTree.Archived && previousTrashedAt is { } trashedAt && now >= trashedAt.AddDays(30))
                    return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "category_tree_retention_expired");
            }
            if (incoming is PaletteEntity incomingPalette)
            {
                if (previousPalettePurgedAt is not null)
                    return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "palette_purged");
                if (paletteWasArchived && !incomingPalette.Archived && previousPaletteTrashedAt is { } trashedAt && now >= trashedAt.AddDays(30))
                    return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "palette_retention_expired");
            }
            if (incoming is CategoryEntity incomingCategory)
            {
                if (priorCategory?.PurgedAt is not null)
                    return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "category_purged");
                if (priorCategory?.Archived == true && !incomingCategory.Archived && priorCategory.TrashedAt is { } categoryTrashedAt && now >= categoryTrashedAt.AddHours(48))
                    return new MutationResult(mutation.ClientMutationId, "rejected", ErrorCode: "category_retention_expired");
            }
            CopyMutableProperties(incoming, entity);
            if (entity is CategoryTreeEntity tree)
            {
                tree.TrashedAt = tree.Archived ? previousTrashedAt ?? now : null;
                tree.PurgedAt = null;
            }
            if (entity is PaletteEntity palette)
            {
                palette.TrashedAt = palette.Archived ? previousPaletteTrashedAt ?? now : null;
                palette.PurgedAt = null;
            }
            if (entity is CategoryEntity category)
            {
                category.TrashedAt = category.Archived ? priorCategory?.TrashedAt ?? now : null;
                category.PurgedAt = null;
            }
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
        if (entity is PlanEntity deletedPlan && deletedPlan.DeletedAt is not null)
        {
            var budgets = await db.BudgetAllocations
                .Where(x => x.PlanId == deletedPlan.Id && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null)
                .ToArrayAsync(cancellationToken);
            var plannedEvents = await db.PlannedEvents
                .Where(x => x.PlanId == deletedPlan.Id && x.WorkspaceId == mutation.WorkspaceId && x.DeletedAt == null)
                .ToArrayAsync(cancellationToken);
            foreach (var child in budgets.Cast<SyncEntity>().Concat(plannedEvents))
            {
                child.DeletedAt = now;
                child.Revision++;
                child.UpdatedAt = now;
                db.ChangeFeed.Add(new ChangeFeedEntry
                {
                    WorkspaceId = mutation.WorkspaceId,
                    EntityType = child is BudgetAllocationEntity ? "budgetAllocation" : "plannedEvent",
                    EntityId = child.Id,
                    Revision = child.Revision,
                    Deleted = true,
                    PayloadJson = JsonSerializer.Serialize(child, child.GetType(), JsonOptions),
                    CreatedAt = now
                });
            }
        }
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
                         and not nameof(SyncEntity.DeletedAt)
                         and not nameof(CategoryTreeEntity.TrashedAt)
                         and not nameof(CategoryTreeEntity.PurgedAt)))
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
