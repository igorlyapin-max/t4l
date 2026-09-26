package app.t4l

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineDateTest {
    @Test
    fun groupsEventsByDisplayedLocalDayAcrossMidnight() {
        val zone = ZoneId.of("Europe/Moscow")
        val beforeMidnight = Instant.parse("2026-09-22T20:59:00Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-09-22T21:01:00Z").toEpochMilli()

        assertEquals(LocalDate.of(2026, 9, 22), timelineLocalDate(beforeMidnight, zone))
        assertEquals(LocalDate.of(2026, 9, 23), timelineLocalDate(afterMidnight, zone))
    }
}
