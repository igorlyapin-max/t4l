package app.t4l.domain

data class EventPoint(
    val id: String,
    val timelineId: String,
    val categoryId: String?,
    val occurredAtEpochMs: Long,
)

data class TimeInterval(
    val timelineId: String,
    val categoryId: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
) {
    val durationSeconds: Long get() = ((endsAtEpochMs - startsAtEpochMs) / 1_000).coerceAtLeast(0)
}

data class CategoryNode(val id: String, val timelineId: String, val parentId: String?)
data class AggregateDuration(val timelineId: String, val categoryId: String?, val durationSeconds: Long)

object TimeEngine {
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
                    if (end > start) TimeInterval(timelineId, event.categoryId, start, end) else null
                }
            }
            .sortedWith(compareBy(TimeInterval::startsAtEpochMs, TimeInterval::timelineId))
    }

    fun aggregate(intervals: List<TimeInterval>, categories: List<CategoryNode>): List<AggregateDuration> {
        val categoryMap = categories.associateBy { it.id }
        val totals = mutableMapOf<Pair<String, String?>, Long>()
        intervals.forEach { interval ->
            fun add(categoryId: String?) {
                val key = interval.timelineId to categoryId
                totals[key] = totals.getOrDefault(key, 0) + interval.durationSeconds
            }
            add(interval.categoryId)
            val visited = mutableSetOf<String>()
            var parentId = interval.categoryId?.let(categoryMap::get)?.parentId
            while (parentId != null && visited.add(parentId)) {
                add(parentId)
                parentId = categoryMap[parentId]?.parentId
            }
        }
        return totals.map { (key, seconds) -> AggregateDuration(key.first, key.second, seconds) }
            .sortedWith(compareBy(AggregateDuration::timelineId, AggregateDuration::categoryId))
    }

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
