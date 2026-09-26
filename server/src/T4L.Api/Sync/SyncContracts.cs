using System.Text.Json;

namespace T4L.Api.Sync;

public sealed record MutationDto(
    Guid ClientMutationId,
    Guid WorkspaceId,
    string EntityType,
    Guid EntityId,
    string Operation,
    long BaseRevision,
    JsonElement Payload,
    Guid? AtomicGroupId = null);

public sealed record PushRequest(Guid ClientId, IReadOnlyList<MutationDto> Mutations);

public sealed record MutationResult(
    Guid ClientMutationId,
    string Status,
    long? Revision = null,
    JsonElement? ServerEntity = null,
    string? ErrorCode = null,
    Guid? CanonicalEntityId = null,
    bool Duplicate = false);

public sealed record PushResponse(IReadOnlyList<MutationResult> Results);

public sealed record ChangeDto(
    long Sequence,
    string EntityType,
    Guid EntityId,
    long Revision,
    bool Deleted,
    JsonElement Payload);

public sealed record ChangePage(IReadOnlyList<ChangeDto> Changes, long NextCursor, bool HasMore);
