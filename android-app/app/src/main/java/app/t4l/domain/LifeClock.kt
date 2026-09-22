package app.t4l.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.roundToLong

data class LifeCountdown(val years: Long, val days: Long, val hours: Long, val minutes: Long, val seconds: Long)
data class LifeProgress(val totalWeeks: Int, val elapsedWeeks: Double, val targetDate: LocalDate)

object LifeClock {
    private const val DAYS_PER_YEAR = 365.2425

    fun targetDate(birthDate: LocalDate, expectancyYears: Double): LocalDate {
        require(expectancyYears in 0.1..130.0)
        val wholeYears = floor(expectancyYears).toLong()
        val fractionalDays = ((expectancyYears - wholeYears) * DAYS_PER_YEAR).roundToLong()
        return birthDate.plusYears(wholeYears).plusDays(fractionalDays)
    }

    fun countdown(birthDate: LocalDate, expectancyYears: Double, now: ZonedDateTime): LifeCountdown {
        val target = targetDate(birthDate, expectancyYears).atStartOfDay(now.zone)
        if (!now.isBefore(target)) return LifeCountdown(0, 0, 0, 0, 0)
        var years = ChronoUnit.YEARS.between(now, target)
        while (now.plusYears(years).isAfter(target)) years--
        val duration = Duration.between(now.plusYears(years), target)
        val days = duration.toDays()
        val hours = duration.minusDays(days).toHours()
        val minutes = duration.minusDays(days).minusHours(hours).toMinutes()
        val seconds = duration.minusDays(days).minusHours(hours).minusMinutes(minutes).seconds
        return LifeCountdown(years, days, hours, minutes, seconds)
    }

    fun progress(birthDate: LocalDate, expectancyYears: Double, now: ZonedDateTime): LifeProgress {
        val birth = birthDate.atStartOfDay(now.zone)
        val target = targetDate(birthDate, expectancyYears).atStartOfDay(now.zone)
        val total = Duration.between(birth, target).toMillis().coerceAtLeast(1)
        val elapsed = Duration.between(birth, now).toMillis().coerceIn(0, total)
        val totalWeeks = kotlin.math.ceil(total.toDouble() / Duration.ofDays(7).toMillis()).toInt()
        return LifeProgress(totalWeeks, elapsed.toDouble() / Duration.ofDays(7).toMillis(), target.toLocalDate())
    }
}
