package com.app.shadetube.ui.nowplaying

import android.content.res.Resources
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.playback.CurrentSource
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.Status
import java.util.Locale
import kotlin.math.roundToInt

/** Texts of the Now Playing top bar and source badge. */
internal object NowPlayingTexts {
    /** (overline, name) of the playing context ("ÇALMA LİSTESİNDEN" / "Gece Sürüşü"). */
    fun context(res: Resources, ctx: PlayContext, kind: ItemKind): Pair<String, String> {
        val uri = ctx.uri
        val overline = when {
            kind == ItemKind.Station || uri.startsWith("iradio:") -> R.string.np_from_station
            kind == ItemKind.Episode || uri.startsWith("podcast:") -> R.string.np_from_podcast
            uri == "liked" -> R.string.player_playing_from_playlist
            uri.startsWith("playlist:") -> R.string.player_playing_from_playlist
            uri.startsWith("album:") -> R.string.player_playing_from_album
            uri.startsWith("artist:") -> R.string.np_from_artist
            uri == "search" -> R.string.np_from_search
            uri == "queue" -> R.string.np_from_queue
            uri == "files" -> R.string.np_from_files
            uri == "history" -> R.string.np_from_history
            uri.startsWith("smart:") -> R.string.np_from_smart
            uri == "link" -> R.string.np_from_link
            uri.startsWith("local") -> R.string.np_from_local
            else -> R.string.np_now_playing
        }
        val name = when {
            ctx.name.isNotEmpty() -> ctx.name
            uri == "liked" -> res.getString(R.string.library_liked_songs)
            uri == "queue" -> res.getString(R.string.queue_title)
            kind == ItemKind.Station -> res.getString(R.string.radio_internet_radio)
            else -> ""
        }
        return res.getString(overline) to name
    }

    /** The plain-text source badge (README: "YouTube · 320 kbps · %94 eşleşme"); null = none (live: the Canlı badge). */
    fun source(res: Resources, src: CurrentSource, status: Status, kind: ItemKind, downloaded: Boolean): String? {
        if (kind == ItemKind.Station || src.live) return null
        val codec = codecName(src.codec, src.mimeType)
        if (src.local) {
            val what = listOfNotNull(codec.takeIf { it.isNotEmpty() }, src.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ")
            return when {
                downloaded -> if (what.isEmpty()) res.getString(R.string.np_source_download_plain) else res.getString(R.string.np_source_download, what)
                kind == ItemKind.Episode -> res.getString(R.string.np_source_episode_file)
                what.isEmpty() -> res.getString(R.string.np_source_local_plain)
                else -> res.getString(R.string.np_source_local, what)
            }
        }
        if (src.direct || kind == ItemKind.Episode) {
            return if (src.bitrateKbps > 0) res.getString(R.string.np_source_podcast_kbps, src.bitrateKbps) else res.getString(R.string.np_source_podcast)
        }
        val m = src.match
        if (m != null) {
            val score = m.score.coerceIn(0.0, 100.0).roundToInt()
            return when {
                m.manual && src.bitrateKbps > 0 -> res.getString(R.string.np_source_manual, src.bitrateKbps)
                m.manual -> res.getString(R.string.np_source_manual_plain)
                src.bitrateKbps > 0 -> res.getString(R.string.player_source_badge, src.bitrateKbps, score)
                else -> res.getString(R.string.np_source_youtube_score, score)
            }
        }
        return if (status == Status.Resolving || status == Status.Buffering) res.getString(R.string.np_source_resolving) else null
    }

    fun codecName(codec: String, mime: String): String {
        val c = (codec + " " + mime).lowercase(Locale.ROOT)
        return when {
            "opus" in c -> "Opus"
            "flac" in c -> "FLAC"
            "mp4a" in c || "aac" in c || "audio/mp4" in c || "m4a" in c -> "AAC"
            "mpeg" in c || "mp3" in c -> "MP3"
            "vorbis" in c || "ogg" in c -> "Ogg"
            "wav" in c -> "WAV"
            "alac" in c -> "ALAC"
            else -> codec.uppercase(Locale.ROOT).take(8)
        }
    }

    /** "1.25" / "1" (speed labels; the resource adds ×). */
    fun speed(v: Float): String {
        val r = (v * 100).roundToInt() / 100f
        return if (r == r.toInt().toFloat()) String.format(Locale.ROOT, "%.1f", r) else {
            String.format(Locale.ROOT, "%.2f", r).trimEnd('0')
        }
    }

    fun artistAlbum(t: Track): String = listOf(t.artistLine, t.album.name).filter { it.isNotEmpty() }.joinToString(" · ")
}
