using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;

namespace T4L.Api.Persistence;

public sealed class T4LDbContext(DbContextOptions<T4LDbContext> options) : DbContext(options)
{
    public DbSet<UserEntity> Users => Set<UserEntity>();
    public DbSet<UserProfileEntity> UserProfiles => Set<UserProfileEntity>();
    public DbSet<ProcessedProfileMutationEntity> ProcessedProfileMutations => Set<ProcessedProfileMutationEntity>();
    public DbSet<WorkspaceEntity> Workspaces => Set<WorkspaceEntity>();
    public DbSet<MembershipEntity> Memberships => Set<MembershipEntity>();
    public DbSet<CategoryTreeEntity> CategoryTrees => Set<CategoryTreeEntity>();
    public DbSet<CategoryEntity> Categories => Set<CategoryEntity>();
    public DbSet<TimeEventEntity> Events => Set<TimeEventEntity>();
    public DbSet<TaskEntity> Tasks => Set<TaskEntity>();
    public DbSet<TaskCommentEntity> TaskComments => Set<TaskCommentEntity>();
    public DbSet<PlanEntity> Plans => Set<PlanEntity>();
    public DbSet<BudgetAllocationEntity> BudgetAllocations => Set<BudgetAllocationEntity>();
    public DbSet<PlannedEventEntity> PlannedEvents => Set<PlannedEventEntity>();
    public DbSet<ChangeFeedEntry> ChangeFeed => Set<ChangeFeedEntry>();
    public DbSet<ProcessedMutationEntity> ProcessedMutations => Set<ProcessedMutationEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<UserEntity>().HasKey(x => x.Id);
        modelBuilder.Entity<UserEntity>().HasIndex(x => x.ExternalSubject).IsUnique();
        modelBuilder.Entity<UserProfileEntity>().HasKey(x => x.UserId);
        modelBuilder.Entity<UserProfileEntity>().HasOne<UserEntity>().WithOne().HasForeignKey<UserProfileEntity>(x => x.UserId);
        modelBuilder.Entity<UserProfileEntity>().Property(x => x.LifeExpectancyYears).HasPrecision(5, 2);
        modelBuilder.Entity<ProcessedProfileMutationEntity>().HasKey(x => new { x.UserId, x.ClientMutationId, x.Kind });
        modelBuilder.Entity<ProcessedProfileMutationEntity>().HasOne<UserEntity>().WithMany().HasForeignKey(x => x.UserId);
        modelBuilder.Entity<WorkspaceEntity>().HasKey(x => x.Id);
        modelBuilder.Entity<MembershipEntity>().HasKey(x => new { x.WorkspaceId, x.UserId });
        modelBuilder.Entity<MembershipEntity>().HasOne<WorkspaceEntity>().WithMany().HasForeignKey(x => x.WorkspaceId);
        modelBuilder.Entity<MembershipEntity>().HasOne<UserEntity>().WithMany().HasForeignKey(x => x.UserId);

        ConfigureSync<CategoryTreeEntity>(modelBuilder);
        ConfigureSync<CategoryEntity>(modelBuilder);
        ConfigureSync<TimeEventEntity>(modelBuilder);
        ConfigureSync<TaskEntity>(modelBuilder);
        ConfigureSync<TaskCommentEntity>(modelBuilder);
        ConfigureSync<PlanEntity>(modelBuilder);
        ConfigureSync<BudgetAllocationEntity>(modelBuilder);
        ConfigureSync<PlannedEventEntity>(modelBuilder);

        modelBuilder.Entity<CategoryEntity>().HasIndex(x => new { x.WorkspaceId, x.CategoryTreeId, x.ParentId });
        modelBuilder.Entity<TimeEventEntity>().HasIndex(x => new { x.WorkspaceId, x.CategoryTreeId, x.OccurredAt, x.Id });
        modelBuilder.Entity<TimeEventEntity>().HasIndex(x => new { x.WorkspaceId, x.TaskId, x.OccurredAt, x.Id })
            .HasFilter("\"DeletedAt\" IS NULL AND \"TaskId\" IS NOT NULL");
        modelBuilder.Entity<TaskEntity>().HasIndex(x => new { x.WorkspaceId, x.CategoryId });
        modelBuilder.Entity<TaskEntity>().HasIndex(x => x.ParentTaskId);
        modelBuilder.Entity<TaskEntity>().HasIndex(x => new { x.WorkspaceId, x.SortOrder });
        modelBuilder.Entity<TaskCommentEntity>().HasIndex(x => new { x.WorkspaceId, x.TaskId, x.CreatedAt });
        modelBuilder.Entity<PlanEntity>().HasIndex(x => new { x.WorkspaceId, x.StartsAt, x.EndsAt });
        modelBuilder.Entity<BudgetAllocationEntity>().HasIndex(x => new { x.WorkspaceId, x.PlanId, x.CategoryId }).IsUnique();
        modelBuilder.Entity<PlannedEventEntity>().HasIndex(x => new { x.PlanId, x.CategoryTreeId, x.OccurredAt });

        modelBuilder.Entity<ChangeFeedEntry>().HasKey(x => x.Sequence);
        modelBuilder.Entity<ChangeFeedEntry>().Property(x => x.Sequence).ValueGeneratedOnAdd();
        modelBuilder.Entity<ChangeFeedEntry>().HasIndex(x => new { x.WorkspaceId, x.Sequence });
        modelBuilder.Entity<ProcessedMutationEntity>().HasKey(x => new { x.ClientId, x.WorkspaceId, x.ClientMutationId });
    }

    private static void ConfigureSync<TEntity>(ModelBuilder modelBuilder) where TEntity : SyncEntity
    {
        modelBuilder.Entity<TEntity>().HasKey(x => x.Id);
        modelBuilder.Entity<TEntity>().HasIndex(x => new { x.WorkspaceId, x.UpdatedAt });
        modelBuilder.Entity<TEntity>().Property(x => x.Revision).IsConcurrencyToken();
    }
}
