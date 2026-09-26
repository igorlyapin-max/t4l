package app.t4l

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DateTimePresetsTest {
    private val now = LocalDateTime.of(2026, 9, 24, 10, 37, 48)

    @Test fun factPresetsUseMinutePrecisionAndOriginalEventForRelativeOffsets() {
        val original = LocalDateTime.of(2026, 9, 23, 9, 45)
        assertEquals(LocalDateTime.of(2026, 9, 24, 10, 37), dateTimePreset(DateTimePresets.FACT_NEW, 0, now, null))
        assertEquals(LocalDateTime.of(2026, 9, 24, 10, 22), dateTimePreset(DateTimePresets.FACT_NEW, 1, now, null))
        assertEquals(original.minusMinutes(15), dateTimePreset(DateTimePresets.FACT_EDIT, 1, now, original))
        assertEquals(original.minusHours(1), dateTimePreset(DateTimePresets.FACT_EDIT, 2, now, original))
    }

    @Test fun planAndDeadlinePresetsUseTheirContext() {
        val start = LocalDateTime.of(2026, 9, 25, 9, 0)
        assertEquals(start.plusHours(1), dateTimePreset(DateTimePresets.PLAN_END, 0, now, start))
        assertEquals(start.plusWeeks(1), dateTimePreset(DateTimePresets.PLAN_END, 2, now, start))
        assertEquals(LocalDateTime.of(2026, 9, 24, 23, 59), dateTimePreset(DateTimePresets.DEADLINE, 0, now, null))
        assertEquals(start.plusMinutes(15), dateTimePreset(DateTimePresets.PLANNED_EVENT, 1, now, start))
    }

    @Test fun distributionPresetsSupportOpenEndAndWeekStart() {
        val today = LocalDate.of(2026, 9, 24)
        assertEquals("2026-09-21", datePreset(DatePresets.DISTRIBUTION_START, 2, today))
        assertEquals("", datePreset(DatePresets.DISTRIBUTION_END, 2, today))
        assertEquals("2026-10-01", datePreset(DatePresets.NEXT_ACTION, 2, today))
    }

    @Test fun planStartShiftPreservesElapsedDuration() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals("2026-03-29 04:30", shiftedPlanEnd("2026-03-28 01:30", "2026-03-28 03:30", "2026-03-29 01:30", zone))
    }
}
