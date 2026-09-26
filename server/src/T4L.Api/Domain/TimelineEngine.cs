namespace T4L.Api.Domain;

public sealed record DerivedInterval(
    Guid CategoryTreeId,
    Guid? CategoryId,
    DateTimeOffset StartsAt,
    DateTimeOffset EndsAt,
    Guid? TaskId = null)
{
    public long DurationMillis => Math.Max(0, (long)(EndsAt - StartsAt).TotalMilliseconds);
    public long DurationSeconds => Math.Max(0, (long)(EndsAt - StartsAt).TotalSeconds);
}

public sealed record AggregateDuration(Guid CategoryTreeId, Guid? CategoryId, long DurationSeconds);
public sealed record DetailedDuration(Guid CategoryTreeId, Guid CategoryId, long OwnMillis, long TotalMillis);
public sealed record TimelinePoint(Guid Id, Guid CategoryTreeId, Guid? CategoryId, Guid? TaskId, DateTimeOffset OccurredAt);

public static class TimelineEngine
{
    public static IReadOnlyList<DerivedInterval> BuildIntervals(
        IEnumerable<TimeEventEntity> source,
        DateTimeOffset from,
        DateTimeOffset to,
        DateTimeOffset now)
    {
        if (to <= from)
        {
            throw new ArgumentException("Report end must be after report start.");
        }

        var effectiveEnd = to < now ? to : now;
        if (effectiveEnd <= from)
        {
            return [];
        }

        return BuildPointIntervals(source.Where(x => x.DeletedAt is null).Select(x => new TimelinePoint(x.Id, x.CategoryTreeId, x.CategoryId, x.TaskId, x.OccurredAt)), from, effectiveEnd);
    }

    public static IReadOnlyList<DerivedInterval> BuildPointIntervals(
        IEnumerable<TimelinePoint> source, DateTimeOffset from, DateTimeOffset to)
    {
        if (to <= from) return [];
        var result = new List<DerivedInterval>();
        foreach (var timeline in source.Where(x => x.OccurredAt < to).GroupBy(x => x.CategoryTreeId))
        {
            var events = timeline.OrderBy(x => x.OccurredAt).ThenBy(x => x.Id).ToArray();
            for (var index = 0; index < events.Length; index++)
            {
                var current = events[index];
                var next = index + 1 < events.Length ? events[index + 1].OccurredAt : to;
                var start = current.OccurredAt > from ? current.OccurredAt : from;
                var end = next < to ? next : to;
                if (end > start)
                {
                    result.Add(new DerivedInterval(timeline.Key, current.CategoryId, start, end, current.TaskId));
                }
            }
        }

        return result.OrderBy(x => x.StartsAt).ThenBy(x => x.CategoryTreeId).ToArray();
    }

    public static IReadOnlyList<DetailedDuration> Distribution(
        IEnumerable<DerivedInterval> intervals, IEnumerable<CategoryEntity> categories)
    {
        var categoryMap = categories.Where(x => x.DeletedAt is null).ToDictionary(x => x.Id);
        var own = new Dictionary<Guid, long>();
        var total = new Dictionary<Guid, long>();
        foreach (var interval in intervals)
        {
            if (interval.CategoryId is not { } id || !categoryMap.TryGetValue(id, out var category) || category.CategoryTreeId != interval.CategoryTreeId) continue;
            own[id] = own.GetValueOrDefault(id) + interval.DurationMillis;
            var current = category;
            var visited = new HashSet<Guid>();
            while (visited.Add(current.Id))
            {
                total[current.Id] = total.GetValueOrDefault(current.Id) + interval.DurationMillis;
                if (current.ParentId is not { } parent || !categoryMap.TryGetValue(parent, out var ancestor) || ancestor.CategoryTreeId != interval.CategoryTreeId) break;
                current = ancestor;
            }
        }
        return total.Select(x => new DetailedDuration(categoryMap[x.Key].CategoryTreeId, x.Key, own.GetValueOrDefault(x.Key), x.Value))
            .OrderBy(x => x.CategoryTreeId).ThenBy(x => x.CategoryId).ToArray();
    }

    public static long TaskSpentMillis(IEnumerable<DerivedInterval> intervals, Guid taskId) =>
        intervals.Where(x => x.TaskId == taskId).Sum(x => x.DurationMillis);

    public static IReadOnlyList<AggregateDuration> Aggregate(
        IEnumerable<DerivedInterval> intervals,
        IEnumerable<CategoryEntity> categories)
    {
        var categoryMap = categories.Where(x => x.DeletedAt is null).ToDictionary(x => x.Id);
        var totals = new Dictionary<(Guid CategoryTreeId, Guid? CategoryId), long>();

        foreach (var interval in intervals)
        {
            Add(interval.CategoryTreeId, interval.CategoryId, interval.DurationSeconds);
            var parentId = interval.CategoryId is { } id && categoryMap.TryGetValue(id, out var category)
                ? category.ParentId
                : null;
            var visited = new HashSet<Guid>();
            while (parentId is { } parent && visited.Add(parent) && categoryMap.TryGetValue(parent, out var ancestor))
            {
                Add(interval.CategoryTreeId, parent, interval.DurationSeconds);
                parentId = ancestor.ParentId;
            }
        }

        return totals
            .Select(x => new AggregateDuration(x.Key.CategoryTreeId, x.Key.CategoryId, x.Value))
            .OrderBy(x => x.CategoryTreeId)
            .ThenBy(x => x.CategoryId)
            .ToArray();

        void Add(Guid timelineId, Guid? categoryId, long seconds)
        {
            var key = (timelineId, categoryId);
            totals[key] = totals.GetValueOrDefault(key) + seconds;
        }
    }

    public static long Intersect(
        IEnumerable<DerivedInterval> intervals,
        IReadOnlyDictionary<Guid, IReadOnlySet<Guid?>> filters)
    {
        if (filters.Count < 2)
        {
            throw new ArgumentException("At least two timeline filters are required.", nameof(filters));
        }

        var matchingByTimeline = filters.ToDictionary(
            pair => pair.Key,
            pair => intervals
                .Where(x => x.CategoryTreeId == pair.Key && pair.Value.Contains(x.CategoryId))
                .OrderBy(x => x.StartsAt)
                .ToArray());

        if (matchingByTimeline.Any(x => x.Value.Length == 0))
        {
            return 0;
        }

        var boundaries = matchingByTimeline.Values
            .SelectMany(x => x.SelectMany(y => new[] { y.StartsAt, y.EndsAt }))
            .Distinct()
            .OrderBy(x => x)
            .ToArray();
        long total = 0;
        for (var i = 0; i + 1 < boundaries.Length; i++)
        {
            var start = boundaries[i];
            var end = boundaries[i + 1];
            if (matchingByTimeline.Values.All(list => list.Any(x => x.StartsAt <= start && x.EndsAt >= end)))
            {
                total += (long)(end - start).TotalSeconds;
            }
        }

        return total;
    }
}
