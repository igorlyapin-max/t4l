using System.Globalization;
using T4L.Api.Domain;
using Xunit;

namespace T4L.Api.Tests;

public sealed class PlanningEngineTests
{
    [Fact]
    public void FreeWindowsSubtractsOccupiedBlocks()
    {
        var start = DateTimeOffset.Parse("2026-09-20T09:00:00Z", CultureInfo.InvariantCulture);
        var result = PlanningEngine.FreeWindows(
            [new TimeWindow(start, start.AddHours(4))],
            [new TimeWindow(start.AddHours(1), start.AddHours(2))]);

        Assert.Equal(2, result.Count);
        Assert.Equal(60, result[0].DurationMinutes);
        Assert.Equal(120, result[1].DurationMinutes);
    }

    [Fact]
    public void PlanningFactorUsesMedianAfterThreeSamples()
    {
        var factor = PlanningEngine.PlanningFactor([(60, 120), (60, 90), (60, 180)]);
        Assert.Equal(2m, factor);
    }

    [Fact]
    public void RecommendDoesNotReturnNonSplittableTaskThatCannotFit()
    {
        var now = DateTimeOffset.Parse("2026-09-20T09:00:00Z", CultureInfo.InvariantCulture);
        var tasks = new[]
        {
            new TaskEntity { Id = Guid.NewGuid(), RemainingEstimateMinutes = 90, Value = 100, Energy = EnergyLevel.High, Splittable = false },
            new TaskEntity { Id = Guid.NewGuid(), RemainingEstimateMinutes = 30, Value = 50, Energy = EnergyLevel.High, Splittable = false }
        };

        var result = PlanningEngine.Recommend(tasks, 45, EnergyLevel.High, now);

        Assert.Single(result);
        Assert.Equal(30, result[0].SuggestedMinutes);
    }
}
