using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;

namespace T4L.Api.Persistence;

public static class DatabaseBootstrap
{
    public static async Task InitializeAsync(T4LDbContext db, CancellationToken cancellationToken)
    {
        var applied = (await db.Database.GetAppliedMigrationsAsync(cancellationToken)).ToHashSet(StringComparer.Ordinal);
        if (applied.Contains("20260920140138_InitialCreate") && !applied.Contains("20260921150000_GrowthModel"))
        {
            throw new InvalidOperationException(
                "legacy_schema_not_supported: reset the development database before starting this version.");
        }
        await db.Database.MigrateAsync(cancellationToken);
        if (await db.Users.AnyAsync(x => x.Id == DevelopmentIdentity.UserId, cancellationToken)) return;

        db.Users.Add(new UserEntity { Id = DevelopmentIdentity.UserId, DisplayName = "Development user" });
        db.Workspaces.Add(new WorkspaceEntity
        {
            Id = DevelopmentIdentity.WorkspaceId,
            Name = "Personal",
            CreatedAt = DateTimeOffset.UtcNow
        });
        db.Memberships.Add(new MembershipEntity
        {
            UserId = DevelopmentIdentity.UserId,
            WorkspaceId = DevelopmentIdentity.WorkspaceId,
            Role = MembershipRole.Owner
        });
        await db.SaveChangesAsync(cancellationToken);
    }
}
