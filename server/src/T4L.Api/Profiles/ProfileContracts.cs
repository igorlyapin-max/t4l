namespace T4L.Api.Profiles;

public sealed record UserProfileResponse(
    DateOnly? BirthDate,
    decimal? LifeExpectancyYears,
    long Revision,
    long AvatarRevision,
    bool HasAvatar);

public sealed record UserProfilePatchRequest(
    Guid ClientMutationId,
    long BaseRevision,
    IReadOnlyList<string> ChangedFields,
    DateOnly? BirthDate,
    decimal? LifeExpectancyYears);

public sealed record AvatarMutationResponse(long AvatarRevision, bool HasAvatar);
