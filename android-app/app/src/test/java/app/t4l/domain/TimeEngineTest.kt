package app.t4l.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeEngineTest {
    @Test
    fun plannedDistributionRejectsInvalidPeriodWithoutCallingIntervals() {
        val events = listOf(EventPoint("event", "activity", "work", 1_000))
        assertEquals(null, TimeEngine.plannedDistribution(events, emptyList(), 1_000, 1_000))
        assertEquals(null, TimeEngine.plannedDistribution(events, emptyList(), 2_000, 1_000))
    }

    @Test
    fun plannedDistributionExcludesEventsOutsidePeriodWithoutCarryIn() {
        val start = 1_000_000L
        val minute = 60_000L
        val events = listOf(
            EventPoint("before", "activity", "old", start - minute),
            EventPoint("inside", "activity", "work", start + 10 * minute),
            EventPoint("after", "activity", "late", start + 70 * minute),
        )
        val rows = TimeEngine.plannedDistribution(events, emptyList(), start, start + 60 * minute).orEmpty().associateBy { it.categoryId }
        assertEquals(null, rows["old"])
        assertEquals(null, rows["late"])
        assertEquals(50 * minute, rows.getValue("work").ownMillis)
    }

    @Test
    fun intervalsClipToNowAndReportBoundary() {
        val from = 1_000_000L
        val events = listOf(
            EventPoint("1", "activity", "work", from - 60_000),
            EventPoint("2", "activity", null, from + 120_000),
        )
        val intervals = TimeEngine.intervals(events, from, from + 600_000, from + 240_000)
        assertEquals(listOf(120L, 120L), intervals.map { it.durationSeconds })
    }

    @Test
    fun aggregateAddsAncestors() {
        val intervals = listOf(TimeInterval("activity", "coding", 0, 3_600_000))
        val categories = listOf(
            CategoryNode("work", "activity", null),
            CategoryNode("coding", "activity", "work"),
        )
        val report = TimeEngine.aggregate(intervals, categories)
        assertEquals(3_600L, report.single { it.categoryId == "coding" }.durationSeconds)
        assertEquals(3_600L, report.single { it.categoryId == "work" }.durationSeconds)
    }

    @Test
    fun intersectionDoesNotDoublePhysicalTime() {
        val intervals = listOf(
            TimeInterval("activity", "work", 0, 10_000),
            TimeInterval("location", "office", 5_000, 15_000),
        )
        assertEquals(5L, TimeEngine.intersection(intervals, mapOf(
            "activity" to setOf("work"),
            "location" to setOf("office"),
        )))
    }

    @Test
    fun distributionKeepsTreesIndependentAndTaskTimeDirect() {
        val start = 1_000_000L
        val events = listOf(
            EventPoint("1", "activity", "a", start),
            EventPoint("2", "location", "x", start),
            EventPoint("3", "activity", "c", start + 20 * 60_000, "task"),
            EventPoint("4", "location", "z", start + 30 * 60_000),
        )
        val categories = listOf(
            CategoryNode("a", "activity", null), CategoryNode("b", "activity", "a"), CategoryNode("c", "activity", "b"),
            CategoryNode("x", "location", null), CategoryNode("y", "location", "x"), CategoryNode("z", "location", "x"),
        )
        val intervals = TimeEngine.intervals(events, start, start + 60 * 60_000, start + 60 * 60_000)
        val rows = TimeEngine.distribution(intervals, categories).associateBy { it.categoryId }
        assertEquals(20 * 60_000L to 60 * 60_000L, rows.getValue("a").let { it.ownMillis to it.totalMillis })
        assertEquals(0L to 40 * 60_000L, rows.getValue("b").let { it.ownMillis to it.totalMillis })
        assertEquals(40 * 60_000L to 40 * 60_000L, rows.getValue("c").let { it.ownMillis to it.totalMillis })
        assertEquals(30 * 60_000L to 60 * 60_000L, rows.getValue("x").let { it.ownMillis to it.totalMillis })
        assertEquals(null, rows["y"])
        assertEquals(30 * 60_000L to 30 * 60_000L, rows.getValue("z").let { it.ownMillis to it.totalMillis })
        assertEquals(40 * 60_000L, TimeEngine.taskSpentMillis(intervals, "task"))
    }
}
