using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Security;

namespace T4L.Api.Sync;

public sealed record WorkspaceImportRequest(string Name, JsonElement Snapshot);
public sealed record WorkspaceImportResult(Guid WorkspaceId, int ImportedEntities);

public sealed class WorkspaceTransferService(T4LDbContext db, TimeProvider timeProvider, ICurrentActor currentActor)
{
    private static readonly JsonSerializerOptions JsonOptions = CreateJsonOptions();

    public async Task<WorkspaceImportResult> ImportAsync(WorkspaceImportRequest request, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.Name) || request.Name.Length > 120)
            throw new ArgumentException("Workspace name must contain between 1 and 120 characters.");
        if (request.Snapshot.ValueKind != JsonValueKind.Object || !request.Snapshot.TryGetProperty("formatVersion", out var version) || version.ValueKind != JsonValueKind.Number || !version.TryGetInt32(out var formatVersion) || formatVersion is not (4 or 5))
            throw new ArgumentException("Unsupported backup formatVersion. Only formatVersion 4 or 5 is accepted.");
        foreach (var collection in new[] { "categoryTrees", "categories", "events", "tasks", "taskComments", "plans", "budgetAllocations", "plannedEvents", "treeAppearances", "palettes" })
            if (!request.Snapshot.TryGetProperty(collection, out var value) || value.ValueKind != JsonValueKind.Array)
                throw new ArgumentException($"Backup is missing array {collection}.");

        var trees = Read<CategoryTreeEntity>(request.Snapshot, "categoryTrees");
        var categories = Read<CategoryEntity>(request.Snapshot, "categories");
        var events = Read<TimeEventEntity>(request.Snapshot, "events");
        var tasks = Read<TaskEntity>(request.Snapshot, "tasks");
        var comments = Read<TaskCommentEntity>(request.Snapshot, "taskComments");
        var plans = Read<PlanEntity>(request.Snapshot, "plans");
        var allocations = Read<BudgetAllocationEntity>(request.Snapshot, "budgetAllocations");
        var plannedEvents = Read<PlannedEventEntity>(request.Snapshot, "plannedEvents");
        var appearances = Read<TreeAppearanceEntity>(request.Snapshot, "treeAppearances");
        var palettes = Read<PaletteEntity>(request.Snapshot, "palettes");
        if (formatVersion == 4)
            foreach (var palette in palettes)
            {
                try
                {
                    using var legacyOrder = JsonDocument.Parse(palette.ItemOrderJson);
                    if (legacyOrder.RootElement.ValueKind != JsonValueKind.Array ||
                        legacyOrder.RootElement.EnumerateArray().Any(x => x.ValueKind != JsonValueKind.String))
                        throw new ArgumentException("Invalid v4 palette itemOrderJson.");
                    palette.RowsJson = JsonSerializer.Serialize(legacyOrder.RootElement.EnumerateArray()
                        .Select(x => new { id = Guid.NewGuid().ToString("D"), slots = new[] { x.GetString() } }));
                }
                catch (JsonException error) { throw new ArgumentException("Invalid v4 palette itemOrderJson.", error); }
            }
        ValidateSnapshot(trees, categories, events, tasks, comments, plans, allocations, plannedEvents, appearances, palettes);

        var actor = await currentActor.GetAsync(cancellationToken);
        var workspaceId = Guid.CreateVersion7();
        var now = timeProvider.GetUtcNow();
        var treeIds = Remap(trees); var categoryIds = Remap(categories);
        var taskIds = Remap(tasks); var planIds = Remap(plans);

        foreach (var entity in trees)
        {
            entity.Id = treeIds[entity.Id]; Reset(entity, workspaceId, now);
            if (entity.PurgedAt is not null)
            {
                entity.Archived = true;
                entity.TrashedAt = now;
                entity.PurgedAt = now;
            }
            else
            {
                entity.TrashedAt = entity.Archived ? now : null;
            }
        }
        foreach (var entity in categories)
        {
            entity.Id = categoryIds[entity.Id];
            entity.CategoryTreeId = Required(treeIds, entity.CategoryTreeId, "category.categoryTreeId");
            entity.ParentId = entity.ParentId is { } id ? Required(categoryIds, id, "category.parentId") : null;
            Reset(entity, workspaceId, now);
            entity.TrashedAt = entity.Archived ? now : null;
            entity.PurgedAt = entity.PurgedAt is null ? null : now;
        }
        foreach (var entity in tasks)
        {
            entity.Id = taskIds[entity.Id];
            entity.CategoryId = entity.CategoryId is { } id ? Required(categoryIds, id, "task.categoryId") : null;
            entity.ParentTaskId = entity.ParentTaskId is { } parent ? Required(taskIds, parent, "task.parentTaskId") : null;
            Reset(entity, workspaceId, now);
        }
        foreach (var entity in events)
        {
            entity.Id = Guid.CreateVersion7();
            entity.CategoryTreeId = Required(treeIds, entity.CategoryTreeId, "event.categoryTreeId");
            entity.CategoryId = entity.CategoryId is { } id ? Required(categoryIds, id, "event.categoryId") : null;
            entity.TaskId = entity.TaskId is { } task ? Required(taskIds, task, "event.taskId") : null;
            Reset(entity, workspaceId, now);
        }
        foreach (var entity in comments)
        {
            entity.Id = Guid.CreateVersion7(); entity.TaskId = Required(taskIds, entity.TaskId, "taskComment.taskId");
            entity.AuthorId = actor.UserId; Reset(entity, workspaceId, now);
        }
        foreach (var entity in plans) { entity.Id = planIds[entity.Id]; Reset(entity, workspaceId, now); }
        foreach (var entity in allocations)
        {
            entity.PlanId = Required(planIds, entity.PlanId, "budgetAllocation.planId");
            entity.CategoryId = Required(categoryIds, entity.CategoryId, "budgetAllocation.categoryId");
            entity.Id = DeterministicIds.BudgetAllocation(workspaceId, entity.PlanId, entity.CategoryId); Reset(entity, workspaceId, now);
        }
        foreach (var entity in plannedEvents)
        {
            entity.Id = Guid.CreateVersion7(); entity.PlanId = Required(planIds, entity.PlanId, "plannedEvent.planId");
            entity.CategoryTreeId = Required(treeIds, entity.CategoryTreeId, "plannedEvent.categoryTreeId");
            entity.CategoryId = entity.CategoryId is { } id ? Required(categoryIds, id, "plannedEvent.categoryId") : null;
            entity.TaskId = entity.TaskId is { } task ? Required(taskIds, task, "plannedEvent.taskId") : null;
            Reset(entity, workspaceId, now);
        }
        foreach (var entity in appearances)
        {
            entity.CategoryTreeId = Required(treeIds, entity.CategoryTreeId, "treeAppearance.categoryTreeId");
            entity.Id = entity.CategoryTreeId;
            Reset(entity, workspaceId, now);
        }
        foreach (var entity in palettes)
        {
            entity.Id = Guid.CreateVersion7();
            using var colors = JsonDocument.Parse(entity.CategoryColorsJson);
            entity.CategoryColorsJson = JsonSerializer.Serialize(colors.RootElement.EnumerateObject().ToDictionary(
                x => Required(categoryIds, Guid.Parse(x.Name), "palette.categoryId").ToString("D"),
                x => x.Value.ValueKind == JsonValueKind.Null ? null : x.Value.GetString()));
            string? RemapPaletteKey(string? key) {
                if (key is null) return null;
                if (key.StartsWith("t:", StringComparison.Ordinal))
                    return taskIds.TryGetValue(Guid.Parse(key[2..]), out var taskId) ? "t:" + taskId.ToString("D") : null;
                var categoryId = Required(categoryIds, Guid.Parse(key[2..]), "palette.order.category");
                return "c:" + categoryId.ToString("D");
            }
            using var rows = JsonDocument.Parse(entity.RowsJson);
            var remappedRows = rows.RootElement.EnumerateArray().Select(row => new {
                id = Guid.NewGuid().ToString("D"),
                slots = row.GetProperty("slots").EnumerateArray().Select(slot =>
                    slot.ValueKind == JsonValueKind.Null ? null : RemapPaletteKey(slot.GetString())).ToArray()
            }).ToArray();
            entity.RowsJson = JsonSerializer.Serialize(remappedRows);
            entity.ItemOrderJson = JsonSerializer.Serialize(remappedRows.SelectMany(x => x.slots).Where(x => x is not null).ToArray());
            entity.TrashedAt = entity.Archived ? now : null;
            entity.PurgedAt = entity.PurgedAt is null ? null : now;
            Reset(entity, workspaceId, now);
        }

        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        db.Workspaces.Add(new WorkspaceEntity { Id = workspaceId, Name = request.Name.Trim(), CreatedAt = now });
        db.Memberships.Add(new MembershipEntity { WorkspaceId = workspaceId, UserId = actor.UserId, Role = MembershipRole.Owner });
        db.CategoryTrees.AddRange(trees); db.Categories.AddRange(categories); db.Events.AddRange(events);
        db.Tasks.AddRange(tasks); db.TaskComments.AddRange(comments); db.Plans.AddRange(plans);
        db.BudgetAllocations.AddRange(allocations); db.PlannedEvents.AddRange(plannedEvents);
        db.TreeAppearances.AddRange(appearances); db.Palettes.AddRange(palettes);
        AddChanges("categoryTree", trees, now); AddChanges("category", categories, now); AddChanges("event", events, now);
        AddChanges("task", tasks, now); AddChanges("taskComment", comments, now); AddChanges("plan", plans, now);
        AddChanges("budgetAllocation", allocations, now); AddChanges("plannedEvent", plannedEvents, now);
        AddChanges("treeAppearance", appearances, now); AddChanges("palette", palettes, now);
        await db.SaveChangesAsync(cancellationToken); await transaction.CommitAsync(cancellationToken);
        return new WorkspaceImportResult(workspaceId, trees.Count + categories.Count + events.Count + tasks.Count + comments.Count + plans.Count + allocations.Count + plannedEvents.Count + appearances.Count + palettes.Count);
    }

    private void AddChanges<TEntity>(string type, IEnumerable<TEntity> entities, DateTimeOffset now) where TEntity : SyncEntity =>
        db.ChangeFeed.AddRange(entities.Select(entity => new ChangeFeedEntry {
            WorkspaceId = entity.WorkspaceId, EntityType = type, EntityId = entity.Id, Revision = entity.Revision,
            Deleted = false, PayloadJson = JsonSerializer.Serialize(entity, JsonOptions), CreatedAt = now
        }));
    private static Dictionary<Guid, Guid> Remap<TEntity>(IEnumerable<TEntity> entities) where TEntity : SyncEntity =>
        entities.ToDictionary(x => x.Id, _ => Guid.CreateVersion7());
    private static List<TEntity> Read<TEntity>(JsonElement root, string name)
    {
        try
        {
            var items = root.TryGetProperty(name, out var element) ? element.Deserialize<List<TEntity>>(JsonOptions) ?? [] : [];
            if (items.Any(x => x is null)) throw new ArgumentException($"Invalid backup collection: {name}.");
            return items;
        }
        catch (JsonException exception) { throw new ArgumentException($"Invalid backup collection: {name}.", exception); }
    }
    private static Guid Required(Dictionary<Guid, Guid> map, Guid oldId, string field) =>
        map.TryGetValue(oldId, out var value) ? value : throw new ArgumentException($"Broken backup reference: {field}.");

    private static void ValidateSnapshot(
        List<CategoryTreeEntity> trees,
        List<CategoryEntity> categories,
        List<TimeEventEntity> events,
        List<TaskEntity> tasks,
        List<TaskCommentEntity> comments,
        List<PlanEntity> plans,
        List<BudgetAllocationEntity> allocations,
        List<PlannedEventEntity> plannedEvents,
        List<TreeAppearanceEntity> appearances,
        List<PaletteEntity> palettes)
    {
        var total = trees.Count + categories.Count + events.Count + tasks.Count + comments.Count + plans.Count + allocations.Count + plannedEvents.Count + appearances.Count + palettes.Count;
        if (total > 100_000) throw new ArgumentException("Backup contains too many entities.");
        var treeIds = trees.Select(x => x.Id).ToHashSet();
        var categoryById = categories.ToDictionary(x => x.Id);
        var taskById = tasks.ToDictionary(x => x.Id);
        var planById = plans.ToDictionary(x => x.Id);
        if (categories.Any(x => string.IsNullOrWhiteSpace(x.Name) || !treeIds.Contains(x.CategoryTreeId))) throw new ArgumentException("Invalid category graph.");
        AssertAcyclic(categoryById.Keys, id => categoryById[id].ParentId, "Category hierarchy contains a cycle.");
        foreach (var task in tasks)
        {
            if (string.IsNullOrWhiteSpace(task.Title) || (task.CategoryId is null) == (task.ParentTaskId is null) || task.EstimateMinutes < 0 || task.Value is < 0 or > 100 || task.Progress is < 0 or > 100)
                throw new ArgumentException("Invalid task graph.");
            if (task.CategoryId is { } categoryId && !categoryById.ContainsKey(categoryId)) throw new ArgumentException("Broken task category reference.");
            if (task.ParentTaskId is { } parentId && !taskById.ContainsKey(parentId)) throw new ArgumentException("Broken task parent reference.");
        }
        AssertAcyclic(taskById.Keys, id => taskById[id].ParentTaskId, "Task hierarchy contains a cycle.");
        if (events.Any(x => !treeIds.Contains(x.CategoryTreeId) || x.CategoryId is { } id && !categoryById.ContainsKey(id) || x.TaskId is { } taskId && !taskById.ContainsKey(taskId))) throw new ArgumentException("Invalid event references.");
        if (comments.Any(x => string.IsNullOrWhiteSpace(x.Text) || x.Text.Length > 4000 || !taskById.ContainsKey(x.TaskId))) throw new ArgumentException("Invalid task comment.");
        if (plans.Any(x => string.IsNullOrWhiteSpace(x.Name) || x.EndsAt <= x.StartsAt)) throw new ArgumentException("Invalid plan.");
        if (allocations.Any(x => x.OwnMinutes < 0 || !planById.ContainsKey(x.PlanId) || !categoryById.ContainsKey(x.CategoryId))) throw new ArgumentException("Invalid budget allocation.");
        if (allocations.GroupBy(x => (x.PlanId, x.CategoryId)).Any(x => x.Count() > 1)) throw new ArgumentException("Duplicate budget allocation.");
        if (plannedEvents.Any(x => !planById.TryGetValue(x.PlanId, out var plan) || x.OccurredAt < plan.StartsAt || x.OccurredAt >= plan.EndsAt || !treeIds.Contains(x.CategoryTreeId) || x.CategoryId is { } id && !categoryById.ContainsKey(id) || x.TaskId is { } taskId && !taskById.ContainsKey(taskId))) throw new ArgumentException("Invalid planned event.");
        if (appearances.Any(x => x.Id != x.CategoryTreeId || !treeIds.Contains(x.CategoryTreeId) || !System.Text.RegularExpressions.Regex.IsMatch(x.ColorHex, "^#[0-9A-Fa-f]{6}$")) ||
            appearances.Select(x => x.CategoryTreeId).Distinct().Count() != appearances.Count) throw new ArgumentException("Invalid tree appearance.");
        foreach (var palette in palettes)
        {
            var contents = PaletteValidation.Parse(palette);
            if (!contents.CategoryIds.IsSubsetOf(categoryById.Keys)) throw new ArgumentException("Broken palette category reference.");
            // Task keys may outlive deleted tasks; import intentionally removes those keys during remapping.
        }
    }

    private static void AssertAcyclic(IEnumerable<Guid> ids, Func<Guid, Guid?> parent, string message)
    {
        foreach (var id in ids)
        {
            var visited = new HashSet<Guid>(); Guid? current = id;
            while (current is { } value)
            {
                if (!visited.Add(value)) throw new ArgumentException(message);
                current = parent(value);
            }
        }
    }
    private static void Reset(SyncEntity entity, Guid workspaceId, DateTimeOffset now)
    { entity.WorkspaceId = workspaceId; entity.Revision = 1; entity.CreatedAt = now; entity.UpdatedAt = now; entity.DeletedAt = null; }
    private static JsonSerializerOptions CreateJsonOptions()
    {
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        options.Converters.Add(new JsonStringEnumConverter(JsonNamingPolicy.CamelCase));
        return options;
    }
}
