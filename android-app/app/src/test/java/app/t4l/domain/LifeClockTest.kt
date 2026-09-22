package app.t4l.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeClockTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun decimalExpectancyProducesStableTargetDate() {
        assertEquals(LocalDate.of(2084, 8, 7), LifeClock.targetDate(LocalDate.of(2000, 1, 1), 84.6))
    }

    @Test fun countdownClampsAfterTarget() {
        val now = LocalDate.of(2085, 1, 1).atStartOfDay(zone)
        assertEquals(LifeCountdown(0, 0, 0, 0, 0), LifeClock.countdown(LocalDate.of(2000, 1, 1), 80.0, now))
    }

    @Test fun currentWeekIsFractional() {
        val birth = LocalDate.of(2000, 1, 1)
        val progress = LifeClock.progress(birth, 80.0, birth.plusDays(10).atStartOfDay(zone))
        assertEquals(10.0 / 7.0, progress.elapsedWeeks, 0.0001)
        assertTrue(progress.totalWeeks > 4_000)
    }
}
