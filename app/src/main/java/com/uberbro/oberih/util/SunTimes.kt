package com.uberbro.oberih.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Схід і захід сонця для Чикаго (формула NOAA, точність ±2 хв). Ніч = від заходу до світанку. */
object SunTimes {
    private const val LAT = 41.88
    private const val LON = -87.63
    private val ZONE: ZoneId = ZoneId.of("America/Chicago")

    fun isNight(now: ZonedDateTime = ZonedDateTime.now(ZONE)): Boolean {
        val local = now.withZoneSameInstant(ZONE)
        val (rise, set) = riseSet(local.toLocalDate())
        val t = local.toLocalTime()
        return t.isAfter(set) || t.isBefore(rise)
    }

    fun riseSet(date: LocalDate): Pair<LocalTime, LocalTime> {
        val n = date.dayOfYear
        val offsetHours = ZONE.rules.getOffset(date.atStartOfDay()).totalSeconds / 3600.0
        fun calc(rising: Boolean): LocalTime {
            val lngHour = LON / 15
            val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24
            val m = 0.9856 * t - 3.289
            var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
            l = norm(l, 360.0)
            var ra = deg(kotlin.math.atan(0.91764 * kotlin.math.tan(rad(l))))
            ra = norm(ra, 360.0)
            ra += floor(l / 90) * 90 - floor(ra / 90) * 90
            ra /= 15
            val sinDec = 0.39782 * sin(rad(l))
            val cosDec = cos(asin(sinDec))
            val cosH = (cos(rad(90.833)) - sinDec * sin(rad(LAT))) / (cosDec * cos(rad(LAT)))
            val h = (if (rising) 360 - deg(acos(cosH.coerceIn(-1.0, 1.0))) else deg(acos(cosH.coerceIn(-1.0, 1.0)))) / 15
            val localT = h + ra - 0.06571 * t - 6.622
            val ut = norm(localT - lngHour, 24.0)
            val local = norm(ut + offsetHours, 24.0)
            val secs = (local * 3600).toLong().coerceIn(0, 86_399)
            return LocalTime.ofSecondOfDay(secs)
        }
        return calc(true) to calc(false)
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m
}
