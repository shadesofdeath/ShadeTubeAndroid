package com.app.shadetube.library

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/*
 * The pure parts of the C++ formatting helpers (app/AppContext: totalDuration, relativeTime, compactCount, thousands,
 * greeting). The backend returns numbers / enums; the UI picks the strings ("{} sa {} dk", "3 gün önce", "{} B"...).
 */

/** "3 sa 12 dk" / "45 dk": abbreviated units, no plural forms needed. [hours] == 0 -> the minutes-only form. */
data class TotalDuration(val hours: Long, val minutes: Long) {
    val hasHours: Boolean get() = hours > 0
}

fun totalDuration(ms: Long): TotalDuration {
    val min = ms / 60_000
    return if (min < 60) TotalDuration(0, min) else TotalDuration(min / 60, min % 60)
}

/** "az önce" / "{} saat önce" / "{} gün önce" / "{} hafta önce" / "{} ay önce" / a date with the year. */
sealed interface RelativeTime {
    /** No timestamp (0): nothing is shown. */
    data object None : RelativeTime
    data object JustNow : RelativeTime
    data class HoursAgo(val n: Long) : RelativeTime
    data class DaysAgo(val n: Long) : RelativeTime
    data class WeeksAgo(val n: Long) : RelativeTime
    data class MonthsAgo(val n: Long) : RelativeTime

    /** A year or more ago: the UI shows the date with the year ("27 EYL 2023"). */
    data class Date(val unixSeconds: Long) : RelativeTime
}

fun relativeTime(unixSeconds: Long, nowUnix: Long): RelativeTime {
    if (unixSeconds <= 0) return RelativeTime.None
    val d = maxOf(0L, nowUnix - unixSeconds)
    return when {
        d < 3600 -> RelativeTime.JustNow
        d < 86_400 -> RelativeTime.HoursAgo(d / 3600)
        d < 7 * 86_400 -> RelativeTime.DaysAgo(d / 86_400)
        d < 30 * 86_400 -> RelativeTime.WeeksAgo(d / (7 * 86_400))
        d < 365 * 86_400 -> RelativeTime.MonthsAgo(d / (30 * 86_400))
        else -> RelativeTime.Date(unixSeconds)
    }
}

/** "12,4 B" / "1,2 Mn": [number] already formatted (one decimal, the locale's separator); the UI adds the unit. */
data class CompactCount(val number: String, val scale: Scale) {
    enum class Scale { None, Thousand, Million }
}

fun compactCount(n: Long, locale: Locale): CompactCount {
    if (n < 1_000) return CompactCount(n.toString(), CompactCount.Scale.None)
    val million = n >= 1_000_000
    val v = if (million) n / 1_000_000.0 else n / 1_000.0
    // printf("%.1f") then the UI language's decimal separator (no digit grouping).
    val num = String.format(Locale.ROOT, "%.1f", v).replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    return CompactCount(num, if (million) CompactCount.Scale.Million else CompactCount.Scale.Thousand)
}

/** "1.234.567": digit grouping of the UI language. */
fun thousands(n: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(n)

/** Home's two-line headline by the time of day ("Günaydın,\nne dinliyoruz?" ...). */
enum class Greeting { Morning, Afternoon, Evening, Night }

fun greeting(hourOfDay: Int): Greeting = when (hourOfDay) {
    in 5..11 -> Greeting.Morning
    in 12..17 -> Greeting.Afternoon
    in 18..22 -> Greeting.Evening
    else -> Greeting.Night
}

/**
 * The seed of Spotify's radio for an id (C++ radioSeed, app/Radio): the id itself for a spotify:track: / album: /
 * artist: / playlist: URI with a base62 id (at most 64 chars), else "" (no radio: Liked Songs, local lists,
 * MusicBrainz ids). The radio also needs a Spotify session.
 */
fun radioSeed(id: String): String {
    for (kind in listOf("spotify:track:", "spotify:album:", "spotify:artist:", "spotify:playlist:")) {
        if (id.length <= kind.length || !id.startsWith(kind)) continue
        val rest = id.substring(kind.length)
        val base62 = rest.length <= 64 && rest.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
        return if (base62) id else ""
    }
    return ""
}

/** "Spotify bağlantısını kopyala" / "MusicBrainz bağlantısını kopyala" targets. */
object WebLinks {
    fun artist(id: String): String =
        if (CatalogSource.isSpotifyId(id)) "https://open.spotify.com/artist/" + id.substringAfterLast(':')
        else "https://musicbrainz.org/artist/$id"

    fun album(id: String): String =
        if (CatalogSource.isSpotifyId(id)) "https://open.spotify.com/album/" + id.substringAfterLast(':')
        else "https://musicbrainz.org/release-group/$id"

    fun playlist(id: String): String = "https://open.spotify.com/playlist/" + id.substringAfterLast(':')

    /** MusicBrainz recordings only (an id without ':'); "" otherwise. */
    fun recording(id: String): String = if (id.isNotEmpty() && ':' !in id) "https://musicbrainz.org/recording/$id" else ""
}
