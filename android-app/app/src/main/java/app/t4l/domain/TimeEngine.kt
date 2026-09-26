package app.t4l.domain

data class EventPoint(
    val id: String,
    val timelineId: String,
    val categoryId: String?,
    val occurredAtEpochMs: Long,
    val taskId: String? = null,
)

data class TimeInterval(
    val timelineId: String,
    val categoryId: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val taskId: String? = null,
) {
    val durationMillis: Long get() = (endsAtEpochMs - startsAtEpochMs).coerceAtLeast(0)
    val durationSeconds: Long get() = ((endsAtEpochMs - startsAtEpochMs) / 1_000).coerceAtLeast(0)
}

data class CategoryNode(val id: String, val timelineId: String, val parentId: String?)
data class AggregateDuration(val timelineId: String, val categoryId: String?, val durationSeconds: Long)
data class DetailedDuration(val timelineId: String, val categoryId: String?, val ownMillis: Long, val totalMillis: Long)

object TimeEngine {
    fun plannedDistribution(
        events: List<EventPoint>,
        categories: List<CategoryNode>,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<DetailedDuration>? {
        if (toEpochMs <= fromEpochMs) return null
        val withinPeriod = events.filter { it.occurredAtEpochMs >= fromEpochMs && it.occurredAtEpochMs < toEpochMs }
        return distribution(intervals(withinPeriod, fromEpochMs, toEpochMs, toEpochMs), categories)
    }

    fun intervals(
        events: List<EventPoint>,
        fromEpochMs: Long,
        toEpochMs: Long,
        nowEpochMs: Long,
    ): List<TimeInterval> {
        require(toEpochMs > fromEpochMs) { "Report end must be after report start." }
        val effectiveEnd = minOf(toEpochMs, nowEpochMs)
        if (effectiveEnd <= fromEpochMs) return emptyList()

        return events
            .filter { it.occurredAtEpochMs < effectiveEnd }
            .groupBy { it.timelineId }
            .flatMap { (timelineId, timelineEvents) ->
                val sorted = timelineEvents.sortedWith(compareBy(EventPoint::occurredAtEpochMs, EventPoint::id))
                sorted.mapIndexedNotNull { index, event ->
                    val next = sorted.getOrNull(index + 1)?.occurredAtEpochMs ?: effectiveEnd
                    val start = maxOf(event.occurredAtEpochMs, fromEpochMs)
                    val end = minOf(next, effectiveEnd)
                    if (end > start) TimeInterval(timelineId, event.categoryId, start, end, event.taskId) else null
                }
            }
            .sortedWith(compareBy(TimeInterval::startsAtEpochMs, TimeInterval::timelineId))
    }

    fun distribution(intervals: List<TimeInterval>, categories: List<CategoryNode>): List<DetailedDuration> {
        val categoryMap = categories.associateBy { it.id }
        val own = mutableMapOf<Pair<String, String?>, Long>()
        val totals = mutableMapOf<Pair<String, String?>, Long>()
        intervals.forEach { interval ->
            val ownKey = interval.timelineId to interval.categoryId
            own[ownKey] = own.getOrDefault(ownKey, 0) + interval.durationMillis
            fun add(categoryId: String?) {
                val key = interval.timelineId to categoryId
                totals[key] = totals.getOrDefault(key, 0) + interval.durationMillis
            }
            add(interval.categoryId)
            val visited = mutableSetOf<String>()
            var parentId = interval.categoryId?.let(categoryMap::get)?.parentId
            while (parentId != null && visited.add(parentId)) {
                val parent = categoryMap[parentId] ?: break
                if (parent.timelineId != interval.timelineId) break
                add(parentId)
                parentId = parent.parentId
            }
        }
        return totals.map { (key, total) -> DetailedDuration(key.first, key.second, own[key] ?: 0, total) }
            .sortedWith(compareBy(DetailedDuration::timelineId, DetailedDuration::categoryId))
    }

    fun aggregate(intervals: List<TimeInterval>, categories: List<CategoryNode>): List<AggregateDuration> =
        distribution(intervals, categories).map { AggregateDuration(it.timelineId, it.categoryId, it.totalMillis / 1_000) }

    fun taskSpentMillis(intervals: List<TimeInterval>, taskId: String): Long =
        intervals.filter { it.taskId == taskId }.sumOf(TimeInterval::durationMillis)

    fun intersection(
        intervals: List<TimeInterval>,
        filters: Map<String, Set<String?>>,
    ): Long {
        require(filters.size >= 2) { "At least two timeline filters are required." }
        val matching = filters.mapValues { (timelineId, categories) ->
            intervals.filter { it.timelineId == timelineId && it.categoryId in categories }
        }
        if (matching.values.any(List<TimeInterval>::isEmpty)) return 0
        val boundaries = matching.values.flatten()
            .flatMap { listOf(it.startsAtEpochMs, it.endsAtEpochMs) }
            .distinct().sorted()
        return boundaries.zipWithNext().sumOf { (start, end) ->
            if (matching.values.all { list -> list.any { it.startsAtEpochMs <= start && it.endsAtEpochMs >= end } })
                (end - start) / 1_000 else 0
        }
    }
}
