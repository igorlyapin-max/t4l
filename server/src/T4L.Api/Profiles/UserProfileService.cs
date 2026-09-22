using Microsoft.EntityFrameworkCore;
using T4L.Api.Domain;
using T4L.Api.Persistence;
using T4L.Api.Security;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Formats.Jpeg;
using SixLabors.ImageSharp.Processing;

namespace T4L.Api.Profiles;

public sealed class UserProfileService(
    T4LDbContext db,
    ICurrentActor currentActor,
    TimeProvider clock)
{
    public async Task<UserProfileResponse> GetAsync(CancellationToken ct)
    {
        var actor = await currentActor.GetAsync(ct);
        var profile = await GetOrCreateAsync(actor.UserId, ct);
        return ToResponse(profile);
    }

    public async Task<(bool Conflict, UserProfileResponse Profile)> PatchAsync(UserProfilePatchRequest request, CancellationToken ct)
    {
        var actor = await currentActor.GetAsync(ct);
        await GetOrCreateAsync(actor.UserId, ct);
        db.ChangeTracker.Clear();
        var profile = await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct);
        if (await WasProcessedAsync(actor.UserId, request.ClientMutationId, "profile", ct))
            return (false, ToResponse(profile));
        if (request.BaseRevision != profile.Revision) return (true, ToResponse(profile));

        var fields = request.ChangedFields.ToHashSet(StringComparer.Ordinal);
        if (fields.Count is < 1 or > 2 || fields.Any(x => x is not ("birthDate" or "lifeExpectancyYears")))
            throw new ArgumentException("changedFields contains an unsupported profile field.");
        if (fields.Contains("birthDate"))
        {
            if (request.BirthDate is { } date && date > DateOnly.FromDateTime(clock.GetUtcNow().UtcDateTime))
                throw new ArgumentException("birthDate cannot be in the future.");
        }
        if (fields.Contains("lifeExpectancyYears"))
        {
            if (request.LifeExpectancyYears is { } years && (years < 0.1m || years > 130m))
                throw new ArgumentException("lifeExpectancyYears must be between 0.1 and 130.");
        }
        var now = clock.GetUtcNow();
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        var updated = fields.SetEquals(["birthDate", "lifeExpectancyYears"])
            ? await db.Database.ExecuteSqlInterpolatedAsync($"""
                UPDATE "UserProfiles" SET "BirthDate"={request.BirthDate}, "LifeExpectancyYears"={request.LifeExpectancyYears},
                "Revision"="Revision"+1, "UpdatedAt"={now}
                WHERE "UserId"={actor.UserId} AND "Revision"={request.BaseRevision}
                """, ct)
            : fields.Contains("birthDate")
                ? await db.Database.ExecuteSqlInterpolatedAsync($"""
                    UPDATE "UserProfiles" SET "BirthDate"={request.BirthDate}, "Revision"="Revision"+1, "UpdatedAt"={now}
                    WHERE "UserId"={actor.UserId} AND "Revision"={request.BaseRevision}
                    """, ct)
                : await db.Database.ExecuteSqlInterpolatedAsync($"""
                    UPDATE "UserProfiles" SET "LifeExpectancyYears"={request.LifeExpectancyYears}, "Revision"="Revision"+1, "UpdatedAt"={now}
                    WHERE "UserId"={actor.UserId} AND "Revision"={request.BaseRevision}
                    """, ct);
        if (updated == 0)
        {
            await transaction.RollbackAsync(ct);
            return (true, ToResponse(await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct)));
        }
        Remember(actor.UserId, request.ClientMutationId, "profile", request.BaseRevision + 1);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        db.ChangeTracker.Clear();
        return (false, ToResponse(await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct)));
    }

    public async Task<(bool Found, byte[]? Content, string? ContentType, long Revision)> GetAvatarAsync(CancellationToken ct)
    {
        var actor = await currentActor.GetAsync(ct);
        var profile = await GetOrCreateAsync(actor.UserId, ct);
        return (profile.AvatarContent is not null, profile.AvatarContent, profile.AvatarContentType, profile.AvatarRevision);
    }

    public async Task<(bool Conflict, AvatarMutationResponse Result)> PutAvatarAsync(
        Guid mutationId, long baseRevision, string contentType, byte[] content, CancellationToken ct)
    {
        var canonicalContent = CanonicalizeAvatar(contentType, content);
        var actor = await currentActor.GetAsync(ct);
        await GetOrCreateAsync(actor.UserId, ct);
        db.ChangeTracker.Clear();
        var profile = await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct);
        if (await WasProcessedAsync(actor.UserId, mutationId, "avatar", ct))
            return (false, new(profile.AvatarRevision, profile.AvatarContent is not null));
        if (baseRevision != profile.AvatarRevision)
            return (true, new(profile.AvatarRevision, profile.AvatarContent is not null));
        var now = clock.GetUtcNow();
        const string canonicalContentType = "image/jpeg";
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        var updated = await db.Database.ExecuteSqlInterpolatedAsync($"""
            UPDATE "UserProfiles" SET "AvatarContent"={canonicalContent}, "AvatarContentType"={canonicalContentType},
            "AvatarRevision"="AvatarRevision"+1, "UpdatedAt"={now}
            WHERE "UserId"={actor.UserId} AND "AvatarRevision"={baseRevision}
            """, ct);
        if (updated == 0)
        {
            await transaction.RollbackAsync(ct);
            var current = await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct);
            return (true, new(current.AvatarRevision, current.AvatarContent is not null));
        }
        Remember(actor.UserId, mutationId, "avatar", baseRevision + 1);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return (false, new(baseRevision + 1, true));
    }

    public async Task<(bool Conflict, AvatarMutationResponse Result)> DeleteAvatarAsync(
        Guid mutationId, long baseRevision, CancellationToken ct)
    {
        var actor = await currentActor.GetAsync(ct);
        await GetOrCreateAsync(actor.UserId, ct);
        db.ChangeTracker.Clear();
        var profile = await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct);
        if (await WasProcessedAsync(actor.UserId, mutationId, "avatar", ct))
            return (false, new(profile.AvatarRevision, profile.AvatarContent is not null));
        if (baseRevision != profile.AvatarRevision)
            return (true, new(profile.AvatarRevision, profile.AvatarContent is not null));
        var now = clock.GetUtcNow();
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        var updated = await db.Database.ExecuteSqlInterpolatedAsync($"""
            UPDATE "UserProfiles" SET "AvatarContent"=NULL, "AvatarContentType"=NULL,
            "AvatarRevision"="AvatarRevision"+1, "UpdatedAt"={now}
            WHERE "UserId"={actor.UserId} AND "AvatarRevision"={baseRevision}
            """, ct);
        if (updated == 0)
        {
            await transaction.RollbackAsync(ct);
            var current = await db.UserProfiles.AsNoTracking().SingleAsync(x => x.UserId == actor.UserId, ct);
            return (true, new(current.AvatarRevision, current.AvatarContent is not null));
        }
        Remember(actor.UserId, mutationId, "avatar", baseRevision + 1);
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return (false, new(baseRevision + 1, false));
    }

    private async Task<UserProfileEntity> GetOrCreateAsync(Guid userId, CancellationToken ct)
    {
        var profile = await db.UserProfiles.SingleOrDefaultAsync(x => x.UserId == userId, ct);
        if (profile is not null) return profile;
        profile = new UserProfileEntity { UserId = userId, UpdatedAt = clock.GetUtcNow() };
        db.UserProfiles.Add(profile);
        try
        {
            await db.SaveChangesAsync(ct);
            return profile;
        }
        catch (DbUpdateException)
        {
            db.ChangeTracker.Clear();
            return await db.UserProfiles.SingleAsync(x => x.UserId == userId, ct);
        }
    }

    private Task<bool> WasProcessedAsync(Guid userId, Guid mutationId, string kind, CancellationToken ct) =>
        db.ProcessedProfileMutations.AnyAsync(x => x.UserId == userId && x.ClientMutationId == mutationId && x.Kind == kind, ct);

    private void Remember(Guid userId, Guid mutationId, string kind, long revision) =>
        db.ProcessedProfileMutations.Add(new ProcessedProfileMutationEntity
        {
            UserId = userId,
            ClientMutationId = mutationId,
            Kind = kind,
            ResultRevision = revision,
            ProcessedAt = clock.GetUtcNow()
        });

    private static UserProfileResponse ToResponse(UserProfileEntity profile) => new(
        profile.BirthDate, profile.LifeExpectancyYears, profile.Revision,
        profile.AvatarRevision, profile.AvatarContent is not null);

    private static byte[] CanonicalizeAvatar(string contentType, byte[] content)
    {
        if (content.Length is < 12 or > 2 * 1024 * 1024) throw new ArgumentException("Avatar must be between 12 bytes and 2 MiB.");
        if (contentType is not ("image/jpeg" or "image/png" or "image/webp"))
            throw new ArgumentException("Avatar content type must be JPEG, PNG, or WebP.");
        try
        {
            var info = Image.Identify(content, out var format) ?? throw new ArgumentException("Avatar is not a decodable image.");
            if (info.Width is < 1 or > 4096 || info.Height is < 1 or > 4096 || (long)info.Width * info.Height > 16_000_000)
                throw new ArgumentException("Avatar dimensions exceed the supported limit.");
            if (!string.Equals(format.DefaultMimeType, contentType, StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("Avatar content does not match its content type.");
            using var image = Image.Load(content);
            if (image.Frames.Count != 1) throw new ArgumentException("Animated avatars are not supported.");
            image.Mutate(x => x.AutoOrient().Resize(new ResizeOptions
            {
                Mode = ResizeMode.Max,
                Size = new Size(1024, 1024)
            }).BackgroundColor(Color.White));
            image.Metadata.ExifProfile = null;
            image.Metadata.IccProfile = null;
            image.Metadata.XmpProfile = null;
            using var output = new MemoryStream();
            image.SaveAsJpeg(output, new JpegEncoder { Quality = 88 });
            return output.ToArray();
        }
        catch (UnknownImageFormatException error)
        {
            throw new ArgumentException("Avatar is not a supported image.", error);
        }
        catch (InvalidImageContentException error)
        {
            throw new ArgumentException("Avatar image data is invalid.", error);
        }
    }
}
