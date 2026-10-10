package com.app.shadetube.stats

import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneRules

/**
 * Constants of the listening statistics and civil-date helpers on "local seconds" (seconds since
 * 1970-01-01 00:00 of the local wall clock). Port of the `st::app::listen` namespace (app/ListenStats.h).
 */
object Listen {
    /** A stream: at least 30 s... */
    const val STREAM_MS = 30_000L

    /** ...or half of a track shorter than this. */
    const val SHORT_TRACK_MS = 60_000L

    /** A position delta may exceed the wall time by 1/40 (sampling jitter). */
    const val CLOCK_JITTER_DIV = 40L

    /**
     * Most credited for audio of unknown position: before a backward jump, or the tail when the track changes.
     */
    const val GAP_CREDIT_MS = 5_000L

    /** Back to the first 10 s from further on: a restart (new play). */
    const val RESTART_MS = 10_000L

    /** Shorter plays are not stored. */
    const val MIN_PLAY_MS = 1_000L

    /** Newest plays of this device kept (listening.json). */
    const val MAX_PLAYS = 100_000

    /** Newest imported plays kept (listening-imported.json). */
    const val MAX_IMPORTED_PLAYS = 2_000_000

    /**
     * Import deduplication: a row is a play already known (same song) when both intervals overlap by at least half
     * of the shorter one, or when they ended within [SAME_END_S] of each other with about the same length
     * ([SAME_LENGTH_MS]) - the account-data export only has minute-precision end times.
     */
    const val SAME_END_S = 60L
    const val SAME_LENGTH_MS = 1_000L

    /** The stream rule. durationMs <= 0 = unknown (then only the 30 s rule applies). */
    @JvmStatic
    fun counts(listenedMs: Long, durationMs: Long): Boolean {
        if (durationMs in 1 until SHORT_TRACK_MS) return listenedMs >= durationMs / 2
        return listenedMs >= STREAM_MS
    }

    /** Days since 1970-01-01 (floor). */
    @JvmStatic
    fun dayNumber(localSeconds: Long): Long =
        if (localSeconds >= 0) localSeconds / 86400 else -((-localSeconds + 86399) / 86400)

    /** 0 = Monday .. 6 = Sunday (1970-01-01 was a Thursday). */
    @JvmStatic
    fun weekday(day: Long): Int = (((day % 7) + 7 + 3) % 7).toInt()

    /** Howard Hinnant's days_from_civil (proleptic Gregorian). */
    @JvmStatic
    fun daysFromCivil(year: Int, month: Int, dayOfMonth: Int): Long {
        val y = year.toLong() - (if (month <= 2) 1 else 0)
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + dayOfMonth - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** civil_from_days packed as year * 512 + month * 32 + day (no allocation; see [civil]). */
    @JvmStatic
    fun civilPacked(day: Long): Long {
        val z = day + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return y * 512 + m * 32 + d
    }

    fun packedYear(p: Long): Int = Math.floorDiv(p, 512L).toInt()
    fun packedMonth(p: Long): Int = (Math.floorMod(p, 512L) / 32).toInt()
    fun packedDay(p: Long): Int = (Math.floorMod(p, 512L) % 32).toInt()

    data class CivilDate(val year: Int, val month: Int, val day: Int)

    /** Month 1..12. */
    @JvmStatic
    fun civil(day: Long): CivilDate {
        val p = civilPacked(day)
        return CivilDate(packedYear(p), packedMonth(p), packedDay(p))
    }
}

/**
 * Unix seconds (UTC) -> the same instant on the local wall clock, as seconds since 1970-01-01 00:00 local time.
 * A clock is not thread-safe (one per page / worker).
 */
fun interface LocalClock {
    fun toLocal(unixUtc: Long): Long
}

/** UTC as the "local" clock (an [com.app.shadetube.stats.SmartLists.Input] without a clock, tests). */
val UtcClock = LocalClock { it }

/**
 * The device's time zone with the rules in force at each instant (java.time's tz database carries the historical
 * DST rules, like Windows' dynamic time zone did for the C++ app).
 */
fun systemLocalClock(): LocalClock = zoneClock(ZoneId.systemDefault())

/** A named zone ("Europe/Berlin"); an unknown name gives the device's zone. For tests. */
fun timeZoneClock(zoneId: String): LocalClock =
    zoneClock(runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() })

/**
 * Caches the offset per UTC day: both ends of a day agree on all but the (at most two) transition days a year,
 * which are resolved per hour. Plays arrive in time order, so the last day answers most lookups without hashing.
 */
private fun zoneClock(zone: ZoneId): LocalClock {
    val rules: ZoneRules = zone.rules
    fun offset(unixUtc: Long): Long = rules.getOffset(Instant.ofEpochSecond(unixUtc)).totalSeconds.toLong()
    if (rules.isFixedOffset) {
        val fixed = offset(0)
        return LocalClock { it + fixed }
    }
    val uniform = HashMap<Long, Long>() // UTC day -> offset (the same all day)
    val hourly = HashMap<Long, Long>() // UTC hour -> offset (transition days)
    var lastDay = Long.MIN_VALUE
    var lastOffset = 0L
    var lastUniform = false
    return LocalClock { utc ->
        val day = Listen.dayNumber(utc)
        if (day == lastDay && lastUniform) return@LocalClock utc + lastOffset
        if (day != lastDay) {
            lastDay = day
            val cached = uniform[day]
            if (cached != null) {
                lastOffset = cached
                lastUniform = true
                return@LocalClock utc + cached
            }
            val a = offset(day * 86400)
            val b = offset(day * 86400 + 86399)
            lastUniform = a == b
            lastOffset = a
            if (lastUniform) {
                uniform[day] = a
                return@LocalClock utc + a
            }
        }
        val hour = if (utc >= 0) utc / 3600 else -((-utc + 3599) / 3600)
        val off = hourly.getOrPut(hour) { offset(utc) }
        utc + off
    }
}
