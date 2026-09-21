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
        if (!request.Snapshot.TryGetProperty("formatVersion", out var version) || version.GetInt32() != 2)
            throw new ArgumentException("Unsupported backup formatVersion. Only formatVersion 2 is accepted.");

        var trees = Read<CategoryTreeEntity>(request.Snapshot, "categoryTrees");
        var categories = Read<CategoryEntity>(request.Snapshot, "categories");
        var events = Read<TimeEventEntity>(request.Snapshot, "events");
        var tasks = Read<TaskEntity>(request.Snapshot, "tasks");
        var comments = Read<TaskCommentEntity>(request.Snapshot, "taskComments");
        var plans = Read<PlanEntity>(request.Snapshot, "plans");
        var allocations = Read<BudgetAllocationEntity>(request.Snapshot, "budgetAllocations");
        var plannedEvents = Read<PlannedEventEntity>(request.Snapshot, "plannedEvents");
        ValidateSnapshot(trees, categories, events, tasks, comments, plans, allocations, plannedEvents);

        var actor = await currentActor.GetAsync(cancellationToken);
        var workspaceId = Guid.CreateVersion7();
        var now = timeProvider.GetUtcNow();
        var treeIds = Remap(trees); var categoryIds = Remap(categories);
        var taskIds = Remap(tasks); var planIds = Remap(plans);

        foreach (var entity in trees) { entity.Id = treeIds[entity.Id]; Reset(entity, workspaceId, now); }
        foreach (var entity in categories)
        {
            entity.Id = categoryIds[entity.Id];
            entity.CategoryTreeId = Required(treeIds, entity.CategoryTreeId, "category.categoryTreeId");
            entity.ParentId = entity.ParentId is { } id ? Required(categoryIds, id, "category.parentId") : null;
            Reset(entity, workspaceId, now);
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

        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        db.Workspaces.Add(new WorkspaceEntity { Id = workspaceId, Name = request.Name.Trim(), CreatedAt = now });
        db.Memberships.Add(new MembershipEntity { WorkspaceId = workspaceId, UserId = actor.UserId, Role = MembershipRole.Owner });
        db.CategoryTrees.AddRange(trees); db.Categories.AddRange(categories); db.Events.AddRange(events);
        db.Tasks.AddRange(tasks); db.TaskComments.AddRange(comments); db.Plans.AddRange(plans);
        db.BudgetAllocations.AddRange(allocations); db.PlannedEvents.AddRange(plannedEvents);
        AddChanges("categoryTree", trees, now); AddChanges("category", categories, now); AddChanges("event", events, now);
        AddChanges("task", tasks, now); AddChanges("taskComment", comments, now); AddChanges("plan", plans, now);
        AddChanges("budgetAllocation", allocations, now); AddChanges("plannedEvent", plannedEvents, now);
        await db.SaveChangesAsync(cancellationToken); await transaction.CommitAsync(cancellationToken);
        return new WorkspaceImportResult(workspaceId, trees.Count + categories.Count + events.Count + tasks.Count + comments.Count + plans.Count + allocations.Count + plannedEvents.Count);
    }

    private void AddChanges<TEntity>(string type, IEnumerable<TEntity> entities, DateTimeOffset now) where TEntity : SyncEntity =>
        db.ChangeFeed.AddRange(entities.Select(entity => new ChangeFeedEntry {
            WorkspaceId = entity.WorkspaceId, EntityType = type, EntityId = entity.Id, Revision = entity.Revision,
            Deleted = false, PayloadJson = JsonSerializer.Serialize(entity, JsonOptions), CreatedAt = now
        }));
    private static Dictionary<Guid, Guid> Remap<TEntity>(IEnumerable<TEntity> entities) where TEntity : SyncEntity =>
        entities.ToDictionary(x => x.Id, _ => Guid.CreateVersion7());
    private static List<TEntity> Read<TEntity>(JsonElement root, string name) =>
        root.TryGetProperty(name, out var element) ? element.Deserialize<List<TEntity>>(JsonOptions) ?? [] : [];
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
        List<PlannedEventEntity> plannedEvents)
    {
        var total = trees.Count + categories.Count + events.Count + tasks.Count + comments.Count + plans.Count + allocations.Count + plannedEvents.Count;
        if (total > 100_000) throw new ArgumentException("Backup contains too many entities.");
        var treeIds = trees.Select(x => x.Id).ToHashSet();
        var categoryById = categories.ToDictionary(x => x.Id);
        var taskById = tasks.ToDictionary(x => x.Id);
        var planById = plans.ToDictionary(x => x.Id);
        if (categories.Any(x => string.IsNullOrWhiteSpace(x.Name) || !treeIds.Contains(x.CategoryTreeId))) throw new ArgumentException("Invalid category graph.");
        AssertAcyclic(categoryById.Keys, id => categoryById[id].ParentId, "Category hierarchy contains a cycle.");
        foreach (var task in tasks)
        {
            if (string.IsNullOrWhiteSpace(task.Title) || (task.CategoryId is null) == (task.ParentTaskId is null) || task.EstimateMinutes < 0 || task.RemainingEstimateMinutes < 0 || task.RemainingEstimateMinutes > task.EstimateMinutes || task.Value is < 0 or > 100 || task.Progress is < 0 or > 100)
                throw new ArgumentException("Invalid task graph.");
            if (task.CategoryId is { } categoryId && !categoryById.ContainsKey(categoryId)) throw new ArgumentException("Broken task category reference.");
            if (task.ParentTaskId is { } parentId && !taskById.ContainsKey(parentId)) throw new ArgumentException("Broken task parent reference.");
        }
        AssertAcyclic(taskById.Keys, id => taskById[id].ParentTaskId, "Task hierarchy contains a cycle.");
        if (events.Any(x => !treeIds.Contains(x.CategoryTreeId) || x.CategoryId is { } id && !categoryById.ContainsKey(id) || x.TaskId is { } taskId && !taskById.ContainsKey(taskId))) throw new ArgumentException("Invalid event references.");
        if (comments.Any(x => string.IsNullOrWhiteSpace(x.Text) || x.Text.Length > 4000 || !taskById.ContainsKey(x.TaskId))) throw new ArgumentException("Invalid task comment.");
        if (plans.Any(x => string.IsNullOrWhiteSpace(x.Name) || x.EndsAt <= x.StartsAt)) throw new ArgumentException("Invalid plan.");
        if (allocations.Any(x => x.OwnMinutes < 0 || !planById.TryGetValue(x.PlanId, out var plan) || plan.Kind != PlanKind.Budget || !categoryById.ContainsKey(x.CategoryId))) throw new ArgumentException("Invalid budget allocation.");
        if (allocations.GroupBy(x => (x.PlanId, x.CategoryId)).Any(x => x.Count() > 1)) throw new ArgumentException("Duplicate budget allocation.");
        if (plannedEvents.Any(x => !planById.TryGetValue(x.PlanId, out var plan) || plan.Kind != PlanKind.Timeline || x.OccurredAt < plan.StartsAt || x.OccurredAt >= plan.EndsAt || !treeIds.Contains(x.CategoryTreeId) || x.CategoryId is { } id && !categoryById.ContainsKey(id) || x.TaskId is { } taskId && !taskById.ContainsKey(taskId))) throw new ArgumentException("Invalid planned event.");
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
