package app.t4l

import app.t4l.data.PaletteContents
import app.t4l.data.PaletteRow
import app.t4l.data.PaletteLayoutRow
import app.t4l.data.PlanRow
import java.time.LocalDate
import java.time.ZoneId
import app.t4l.data.validHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContentsTest {
    @Test fun categoryColorsAndMixedOrderRoundTrip() {
        val value = PaletteContents(mapOf("a" to null, "b" to "#123ABC"), listOf("t:task", "c:a", "c:b"),
            listOf(PaletteLayoutRow("row-1", listOf("t:task", null, "c:a")), PaletteLayoutRow("row-2", listOf("c:b"))))
        val row = PaletteRow("id", "workspace", "Palette", value.colorsJson(), value.orderJson(), updatedAtEpochMs = 1, rowsJson = value.rowsJson())
        assertEquals(value, PaletteContents.from(row))
    }

    @Test fun movingAcrossRowsPreservesSourceHoleAndEmptyRow() {
        val start = PaletteContents(order = listOf("c:a", "c:b"), rows = listOf(
            PaletteLayoutRow("first", listOf("c:a", "c:b")), PaletteLayoutRow("empty", listOf(null))))
        val result = start.moved("c:b", "empty", 0)
        assertEquals(listOf("c:a", null), result.rows[0].slots)
        assertEquals(listOf("c:b"), result.rows[1].slots)
        assertEquals(listOf("c:a", "c:b"), result.order)
    }

    @Test fun reorderKeepsHiddenTaskPosition() {
        val order = listOf("c:a", "t:hidden", "c:b", "t:visible")
        assertEquals(listOf("c:b", "c:a", "t:hidden", "t:visible"), movePaletteKey(order, "c:b", "c:a", false))
    }

    @Test fun reorderCanMoveAcrossSeveralVisibleItems() {
        val order = listOf("c:first", "c:second", "t:task", "c:third", "c:last")
        assertEquals(listOf("c:second", "t:task", "c:third", "c:last", "c:first"),
            movePaletteKey(order, "c:first", "c:last", true))
    }

    @Test fun colorsUseOnlyRgbHex() {
        assertTrue(validHexColor("#A0b1C2"))
        assertFalse(validHexColor("#A0B1C2FF"))
        assertFalse(validHexColor("red"))
    }

    @Test fun quickPlannedTimeUsesSelectedDateAndHalfOpenPlanPeriod() {
        val day = LocalDate.of(2026, 10, 7)
        val zone = ZoneId.of("UTC")
        val start = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = PlanRow("plan", "workspace", "Morning", start, end, "UTC", updatedAtEpochMs = 1)
        assertEquals(start, quickPlannedEpoch(day, 9, 0, zone, plan))
        assertEquals(null, quickPlannedEpoch(day, 10, 0, zone, plan))
        assertEquals(null, quickPlannedEpoch(day.minusDays(1), 9, 30, zone, plan))
    }
}
