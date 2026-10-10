package com.app.shadetube.stats

import com.app.shadetube.catalog.AlbumRef
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Image
import com.app.shadetube.catalog.Track
import java.io.File

internal fun track(
    id: String,
    name: String,
    artist: String,
    album: String,
    durationMs: Int,
    artistId: String = "",
    albumId: String = "",
) = Track(
    id = id,
    name = name,
    artists = listOf(ArtistRef(artistId, artist)),
    album = AlbumRef(albumId, album, listOf(Image("https://img.example/$album.jpg", 300, 300))),
    durationMs = durationMs,
)

/** Plays `seconds` of real playback from the current position, sampling every 2 s like the housekeeping tick. */
internal class Sim(val s: ListenStats) {
    var wall = 1_000_000L // monotonic ms
    var pos = 0L // player position ms
    fun unix() = 1_700_000_000L + (wall - 1_000_000) / 1000
    fun sample(playing: Boolean) = s.progress(pos, playing, 0, wall, unix())
    fun play(seconds: Int) {
        var i = 0
        while (i < seconds) {
            val step = minOf(2, seconds - i)
            wall += step * 1000L
            pos += step * 1000L
            sample(true)
            i += 2
        }
    }

    fun pause(seconds: Int) {
        sample(false) // the pause itself (playerChanged)
        var i = 0
        while (i < seconds) {
            wall += 2000
            sample(false)
            i += 2
        }
    }

    fun resume() = sample(true)

    /** seek() fires playerChanged at once: position = the target. */
    fun seekTo(ms: Long) {
        wall += 100
        pos = ms
        sample(true)
    }

    /** n seeks of `jumpMs`, `everyMs` apart, the engine rebuffering after each (no audio in between). */
    fun jumps(n: Int, jumpMs: Long, everyMs: Long) {
        repeat(n) {
            wall += everyMs
            pos += jumpMs
            sample(true)
        }
    }
}

internal fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Long =
    Listen.daysFromCivil(y, mo, d) * 86400 + h * 3600L + mi * 60L + s

internal fun fixture(name: String): File = listOf(
    File("src/test/java/com/app/shadetube/stats/fixtures/$name"),
    File("app/src/test/java/com/app/shadetube/stats/fixtures/$name"),
).firstOrNull { it.exists() } ?: error("fixture $name not found")

/** Makes [file] unreadable (a directory in its place) for [block], like a file locked by a scanner. */
internal fun <T> whileLocked(file: File, block: () -> T): T {
    val moved = File(file.path + ".moved")
    check(file.renameTo(moved))
    check(file.mkdir())
    try {
        return block()
    } finally {
        file.delete()
        check(moved.renameTo(file))
    }
}
