using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Sync;

namespace T4L.Api.Security;

public sealed record ActorContext(Guid UserId, IReadOnlyDictionary<Guid, MembershipRole> Workspaces);

public interface ICurrentActor
{
    Task<ActorContext> GetAsync(CancellationToken cancellationToken);
}

public sealed class CurrentActor(
    IHttpContextAccessor httpContextAccessor,
    T4LDbContext db,
    IConfiguration configuration,
    IHostEnvironment environment,
    TimeProvider timeProvider) : ICurrentActor
{
    public async Task<ActorContext> GetAsync(CancellationToken cancellationToken)
    {
        if (configuration.GetValue<bool>("T4L:DevelopmentInsecure"))
        {
            if (!environment.IsDevelopment()) throw new UnauthorizedAccessException("development_identity_forbidden");
            return await LoadAsync(DevelopmentIdentity.UserId, cancellationToken);
        }

        var principal = httpContextAccessor.HttpContext?.User;
        var subject = principal?.FindFirstValue("sub") ?? principal?.FindFirstValue(ClaimTypes.NameIdentifier);
        if (string.IsNullOrWhiteSpace(subject)) throw new UnauthorizedAccessException("authenticated_subject_required");
        var userId = DeterministicIds.Named($"oidc-user:{subject}");
        if (!await db.Users.AnyAsync(x => x.Id == userId, cancellationToken))
        {
            var now = timeProvider.GetUtcNow();
            var workspaceId = DeterministicIds.Named($"personal-workspace:{subject}");
            db.Users.Add(new UserEntity
            {
                Id = userId,
                ExternalSubject = subject,
                DisplayName = principal?.FindFirstValue("name") ?? subject
            });
            db.Workspaces.Add(new WorkspaceEntity { Id = workspaceId, Name = "Personal", CreatedAt = now });
            db.Memberships.Add(new MembershipEntity { UserId = userId, WorkspaceId = workspaceId, Role = MembershipRole.Owner });
            await db.SaveChangesAsync(cancellationToken);
        }
        return await LoadAsync(userId, cancellationToken);
    }

    private async Task<ActorContext> LoadAsync(Guid userId, CancellationToken cancellationToken)
    {
        var memberships = await db.Memberships.AsNoTracking()
            .Where(x => x.UserId == userId)
            .ToDictionaryAsync(x => x.WorkspaceId, x => x.Role, cancellationToken);
        return new ActorContext(userId, memberships);
    }
}

public sealed class WorkspaceAccess(ICurrentActor actor)
{
    public async Task<ActorContext> RequireAsync(Guid workspaceId, MembershipRole minimumRole, CancellationToken cancellationToken)
    {
        var current = await actor.GetAsync(cancellationToken);
        if (!current.Workspaces.TryGetValue(workspaceId, out var role) || role > minimumRole)
            throw new UnauthorizedAccessException("workspace_access_denied");
        return current;
    }
}
