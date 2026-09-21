namespace T4L.Api.Domain;

public sealed record TimeWindow(DateTimeOffset StartsAt, DateTimeOffset EndsAt)
{
    public int DurationMinutes => Math.Max(0, (int)(EndsAt - StartsAt).TotalMinutes);
}

public sealed record TaskRecommendation(Guid TaskId, int SuggestedMinutes, decimal Score);

public static class PlanningEngine
{
    public static IReadOnlyList<TimeWindow> FreeWindows(
        IEnumerable<TimeWindow> availability,
        IEnumerable<TimeWindow> occupied)
    {
        var result = new List<TimeWindow>();
        var blocks = occupied.OrderBy(x => x.StartsAt).ToArray();
        foreach (var available in availability.OrderBy(x => x.StartsAt))
        {
            var cursor = available.StartsAt;
            foreach (var block in blocks.Where(x => x.EndsAt > available.StartsAt && x.StartsAt < available.EndsAt))
            {
                if (block.StartsAt > cursor)
                {
                    result.Add(new TimeWindow(cursor, block.StartsAt < available.EndsAt ? block.StartsAt : available.EndsAt));
                }
                if (block.EndsAt > cursor)
                {
                    cursor = block.EndsAt;
                }
                if (cursor >= available.EndsAt) break;
            }
            if (cursor < available.EndsAt) result.Add(new TimeWindow(cursor, available.EndsAt));
        }
        return result.Where(x => x.DurationMinutes > 0).ToArray();
    }

    public static IReadOnlyList<TaskRecommendation> Recommend(
        IEnumerable<TaskEntity> tasks,
        int windowMinutes,
        EnergyLevel energy,
        DateTimeOffset now,
        decimal planningFactor = 1m)
    {
        if (windowMinutes <= 0) return [];
        if (planningFactor <= 0) planningFactor = 1m;

        return tasks
            .Where(x => x.DeletedAt is null && x.Status == TaskState.Active)
            .Select(task =>
            {
                var adjusted = Math.Max(1, (int)Math.Ceiling(task.RemainingEstimateMinutes * planningFactor));
                var fits = adjusted <= windowMinutes;
                if (!fits && !task.Splittable) return null;
                var suggested = Math.Min(adjusted, windowMinutes);
                var urgency = task.Deadline is null ? 0m : Math.Max(0m, 30m - (decimal)(task.Deadline.Value - now).TotalDays);
                var energyMatch = task.Energy == energy ? 25m : 0m;
                var completionBonus = fits ? 10m : 0m;
                var score = task.Value + urgency + energyMatch + completionBonus;
                return new TaskRecommendation(task.Id, suggested, score);
            })
            .Where(x => x is not null)
            .Cast<TaskRecommendation>()
            .OrderByDescending(x => x.Score)
            .ThenBy(x => x.SuggestedMinutes)
            .ThenBy(x => x.TaskId)
            .ToArray();
    }

    public static decimal PlanningFactor(IEnumerable<(int EstimateMinutes, int ActualMinutes)> samples)
    {
        var ratios = samples
            .Where(x => x.EstimateMinutes > 0 && x.ActualMinutes >= 0)
            .Select(x => (decimal)x.ActualMinutes / x.EstimateMinutes)
            .OrderBy(x => x)
            .ToArray();
        if (ratios.Length < 3) return 1m;
        var middle = ratios.Length / 2;
        return ratios.Length % 2 == 1 ? ratios[middle] : (ratios[middle - 1] + ratios[middle]) / 2m;
    }
}
