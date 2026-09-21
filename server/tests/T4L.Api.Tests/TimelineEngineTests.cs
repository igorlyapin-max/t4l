using System.Globalization;
using T4L.Api.Domain;
using Xunit;

namespace T4L.Api.Tests;

public sealed class TimelineEngineTests
{
    private static readonly Guid WorkspaceId = Guid.NewGuid();
    private static readonly Guid TimelineId = Guid.NewGuid();
    private static readonly Guid WorkId = Guid.NewGuid();
    private static readonly Guid CodingId = Guid.NewGuid();

    [Fact]
    public void BuildIntervalsClipsToReportAndNow()
    {
        var from = DateTimeOffset.Parse("2026-09-20T09:00:00Z", CultureInfo.InvariantCulture);
        var events = new[]
        {
            Event(from.AddHours(-1), WorkId),
            Event(from.AddHours(2), null)
        };

        var intervals = TimelineEngine.BuildIntervals(events, from, from.AddHours(8), from.AddHours(4));

        Assert.Equal(2, intervals.Count);
        Assert.Equal(7_200, intervals[0].DurationSeconds);
        Assert.Equal(7_200, intervals[1].DurationSeconds);
        Assert.Null(intervals[1].CategoryId);
    }

    [Fact]
    public void AggregateIncludesAncestorsWithoutDoublingPhysicalTime()
    {
        var from = DateTimeOffset.Parse("2026-09-20T09:00:00Z", CultureInfo.InvariantCulture);
        var intervals = new[] { new DerivedInterval(TimelineId, CodingId, from, from.AddHours(2)) };
        var categories = new[]
        {
            Category(WorkId, null),
            Category(CodingId, WorkId)
        };

        var totals = TimelineEngine.Aggregate(intervals, categories);

        Assert.Equal(7_200, totals.Single(x => x.CategoryId == CodingId).DurationSeconds);
        Assert.Equal(7_200, totals.Single(x => x.CategoryId == WorkId).DurationSeconds);
    }

    [Fact]
    public void IntersectCountsOnlyCommonPhysicalRange()
    {
        var otherTimeline = Guid.NewGuid();
        var office = Guid.NewGuid();
        var from = DateTimeOffset.Parse("2026-09-20T09:00:00Z", CultureInfo.InvariantCulture);
        var intervals = new[]
        {
            new DerivedInterval(TimelineId, WorkId, from, from.AddHours(3)),
            new DerivedInterval(otherTimeline, office, from.AddHours(1), from.AddHours(4))
        };

        var total = TimelineEngine.Intersect(intervals, new Dictionary<Guid, IReadOnlySet<Guid?>>
        {
            [TimelineId] = new HashSet<Guid?> { WorkId },
            [otherTimeline] = new HashSet<Guid?> { office }
        });

        Assert.Equal(7_200, total);
    }

    private static TimeEventEntity Event(DateTimeOffset timestamp, Guid? categoryId) => new()
    {
        Id = Guid.NewGuid(), WorkspaceId = WorkspaceId, CategoryTreeId = TimelineId,
        OccurredAt = timestamp, CategoryId = categoryId
    };

    private static CategoryEntity Category(Guid id, Guid? parentId) => new()
    {
        Id = id, WorkspaceId = WorkspaceId, CategoryTreeId = TimelineId,
        ParentId = parentId, Name = id.ToString()
    };
}
