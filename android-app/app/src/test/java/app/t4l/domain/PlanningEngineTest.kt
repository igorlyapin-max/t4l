package app.t4l.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningEngineTest {
    @Test
    fun freeWindowsSubtractOccupiedTime() {
        val windows = PlanningEngine.freeWindows(
            listOf(PlanningWindow(0, 240 * 60_000L)),
            listOf(PlanningWindow(60 * 60_000L, 120 * 60_000L)),
        )
        assertEquals(listOf(60, 120), windows.map { it.durationMinutes })
    }

    @Test
    fun recommendationExcludesTaskThatCannotFit() {
        val recommendations = PlanningEngine.recommend(
            listOf(
                PlanningTask("large", 90, 100, "high", null, false),
                PlanningTask("small", 30, 50, "high", null, false),
            ),
            45,
            "high",
            0,
        )
        assertEquals(listOf("small"), recommendations.map { it.taskId })
    }

    @Test
    fun planningFactorUsesMedian() {
        assertTrue(kotlin.math.abs(PlanningEngine.planningFactor(listOf(60 to 120, 60 to 90, 60 to 180)) - 2.0) < 0.001)
    }
}
