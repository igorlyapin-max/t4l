package app.t4l.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeEngineTest {
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
}
