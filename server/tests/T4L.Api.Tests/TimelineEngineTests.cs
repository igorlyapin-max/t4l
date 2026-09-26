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

    [Fact]
    public void DistributionKeepsTreesIndependentAndCountsOwnAndDescendants()
    {
        var start = DateTimeOffset.Parse("2026-09-20T10:00:00Z", CultureInfo.InvariantCulture);
        var a = Guid.NewGuid(); var b = Guid.NewGuid(); var c = Guid.NewGuid();
        var otherTree = Guid.NewGuid(); var x = Guid.NewGuid(); var y = Guid.NewGuid(); var z = Guid.NewGuid();
        var task = Guid.NewGuid();
        var categories = new[] { Category(a, null), Category(b, a), Category(c, b),
            new CategoryEntity { Id = x, CategoryTreeId = otherTree },
            new CategoryEntity { Id = y, CategoryTreeId = otherTree, ParentId = x },
            new CategoryEntity { Id = z, CategoryTreeId = otherTree, ParentId = x } };
        var points = new[] {
            new TimelinePoint(Guid.NewGuid(), TimelineId, a, null, start),
            new TimelinePoint(Guid.NewGuid(), otherTree, x, null, start),
            new TimelinePoint(Guid.NewGuid(), TimelineId, c, task, start.AddMinutes(20)),
            new TimelinePoint(Guid.NewGuid(), otherTree, z, null, start.AddMinutes(30)) };
        var intervals = TimelineEngine.BuildPointIntervals(points, start, start.AddHours(1));
        var rows = TimelineEngine.Distribution(intervals, categories).ToDictionary(row => row.CategoryId);
        Assert.Equal((20 * 60_000L, 60 * 60_000L), (rows[a].OwnMillis, rows[a].TotalMillis));
        Assert.Equal((0L, 40 * 60_000L), (rows[b].OwnMillis, rows[b].TotalMillis));
        Assert.Equal((40 * 60_000L, 40 * 60_000L), (rows[c].OwnMillis, rows[c].TotalMillis));
        Assert.Equal((30 * 60_000L, 60 * 60_000L), (rows[x].OwnMillis, rows[x].TotalMillis));
        Assert.False(rows.ContainsKey(y));
        Assert.Equal((30 * 60_000L, 30 * 60_000L), (rows[z].OwnMillis, rows[z].TotalMillis));
        Assert.Equal(40 * 60_000L, TimelineEngine.TaskSpentMillis(intervals, task));
    }

    [Fact]
    public void TaskSpentExcludesOtherTasksEvenWhenTheyShareCategory()
    {
        var start = DateTimeOffset.Parse("2026-09-20T10:00:00Z", CultureInfo.InvariantCulture);
        var parent = Guid.NewGuid(); var child = Guid.NewGuid();
        var points = new[] {
            new TimelinePoint(Guid.NewGuid(), TimelineId, WorkId, parent, start),
            new TimelinePoint(Guid.NewGuid(), TimelineId, WorkId, child, start.AddMinutes(20)) };
        var intervals = TimelineEngine.BuildPointIntervals(points, start, start.AddHours(1));
        Assert.Equal(20 * 60_000L, TimelineEngine.TaskSpentMillis(intervals, parent));
        Assert.Equal(40 * 60_000L, TimelineEngine.TaskSpentMillis(intervals, child));
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
