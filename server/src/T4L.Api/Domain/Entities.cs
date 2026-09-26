namespace T4L.Api.Domain;

public static class DevelopmentIdentity
{
    public static readonly Guid UserId = Guid.Parse("018f0000-0000-7000-8000-000000000001");
    public static readonly Guid WorkspaceId = Guid.Parse("018f0000-0000-7000-8000-000000000002");
}

public enum CategoryTreeRole { Standard, Primary, Secondary }
public enum LoadType { Focus, Light, Background, Compatible }
public enum EventSource { Manual, Import, Integration }
public enum EnergyLevel { Low, Medium, High }
public enum TaskState { Active, Paused, Completed, Cancelled }
public enum MembershipRole { Owner, Admin, Editor, Viewer }

public abstract class SyncEntity
{
    public Guid Id { get; set; }
    public Guid WorkspaceId { get; set; }
    public long Revision { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
    public DateTimeOffset? DeletedAt { get; set; }
}

public sealed class UserEntity
{
    public Guid Id { get; set; }
    public string DisplayName { get; set; } = "";
    public string? ExternalSubject { get; set; }
}

public sealed class UserProfileEntity
{
    public Guid UserId { get; set; }
    public DateOnly? BirthDate { get; set; }
    public decimal? LifeExpectancyYears { get; set; }
    public long Revision { get; set; }
    public long AvatarRevision { get; set; }
    public byte[]? AvatarContent { get; set; }
    public string? AvatarContentType { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

public sealed class ProcessedProfileMutationEntity
{
    public Guid UserId { get; set; }
    public Guid ClientMutationId { get; set; }
    public string Kind { get; set; } = "profile";
    public long ResultRevision { get; set; }
    public DateTimeOffset ProcessedAt { get; set; }
}

public sealed class WorkspaceEntity
{
    public Guid Id { get; set; }
    public string Name { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; }
}

public sealed class MembershipEntity
{
    public Guid WorkspaceId { get; set; }
    public Guid UserId { get; set; }
    public MembershipRole Role { get; set; }
}

public sealed class CategoryTreeEntity : SyncEntity
{
    public string Name { get; set; } = "";
    public CategoryTreeRole Role { get; set; }
    public int SortOrder { get; set; }
    public bool Archived { get; set; }
    public DateTimeOffset? TrashedAt { get; set; }
    public DateTimeOffset? PurgedAt { get; set; }
}

public sealed class CategoryEntity : SyncEntity
{
    public Guid CategoryTreeId { get; set; }
    public Guid? ParentId { get; set; }
    public string Name { get; set; } = "";
    public LoadType LoadType { get; set; }
    public int SortOrder { get; set; }
    public bool Archived { get; set; }
}

public sealed class TimeEventEntity : SyncEntity
{
    public Guid CategoryTreeId { get; set; }
    public Guid? CategoryId { get; set; }
    public Guid? TaskId { get; set; }
    public DateTimeOffset OccurredAt { get; set; }
    public string ZoneId { get; set; } = "UTC";
    public EventSource Source { get; set; }
    public string? Note { get; set; }
}

public sealed class TaskEntity : SyncEntity
{
    public string Title { get; set; } = "";
    public Guid? CategoryId { get; set; }
    public Guid? ParentTaskId { get; set; }
    public int EstimateMinutes { get; set; }
    public DateTimeOffset? Deadline { get; set; }
    public DateOnly? NextActionDate { get; set; }
    public TimeOnly? NextActionTime { get; set; }
    public string ZoneId { get; set; } = "UTC";
    public int Value { get; set; }
    public EnergyLevel Energy { get; set; }
    public int Progress { get; set; }
    public TaskState Status { get; set; }
    public bool Splittable { get; set; }
    public int SortOrder { get; set; }
}

public sealed class TaskCommentEntity : SyncEntity
{
    public Guid TaskId { get; set; }
    public Guid AuthorId { get; set; }
    public string Text { get; set; } = "";
}

public sealed class PlanEntity : SyncEntity
{
    public string Name { get; set; } = "";
    public DateTimeOffset StartsAt { get; set; }
    public DateTimeOffset EndsAt { get; set; }
    public string ZoneId { get; set; } = "UTC";
    public bool Archived { get; set; }
}

public sealed class BudgetAllocationEntity : SyncEntity
{
    public Guid PlanId { get; set; }
    public Guid CategoryId { get; set; }
    public int OwnMinutes { get; set; }
}

public sealed class PlannedEventEntity : SyncEntity
{
    public Guid PlanId { get; set; }
    public Guid CategoryTreeId { get; set; }
    public Guid? TaskId { get; set; }
    public Guid? CategoryId { get; set; }
    public DateTimeOffset OccurredAt { get; set; }
    public string? Note { get; set; }
}

public sealed class ChangeFeedEntry
{
    public long Sequence { get; set; }
    public Guid WorkspaceId { get; set; }
    public string EntityType { get; set; } = "";
    public Guid EntityId { get; set; }
    public long Revision { get; set; }
    public bool Deleted { get; set; }
    public string PayloadJson { get; set; } = "{}";
    public DateTimeOffset CreatedAt { get; set; }
}

public sealed class ProcessedMutationEntity
{
    public Guid ClientMutationId { get; set; }
    public Guid ClientId { get; set; }
    public Guid WorkspaceId { get; set; }
    public string ResultJson { get; set; } = "{}";
    public DateTimeOffset ProcessedAt { get; set; }
}
