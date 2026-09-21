namespace T4L.Api.Domain;

public sealed record IntersectionFilter(Guid CategoryTreeId, IReadOnlyList<Guid?> CategoryIds);

public sealed record IntersectionRequest(
    DateTimeOffset From,
    DateTimeOffset To,
    IReadOnlyList<IntersectionFilter> Filters);

public sealed record RecommendationRequest(
    int WindowMinutes,
    EnergyLevel Energy,
    decimal PlanningFactor = 1m);
