package com.app.shadetube.stats

import com.app.shadetube.catalog.Track
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** One smart list (port of smart::List). */
data class SmartList(
    /** "month", "best", "rediscover", "discoveries", "forgotten", "mix:2", "year:2025". */
    val id: String,
    val kind: SmartLists.Kind,
    /** mix: 1..3; year: the year. */
    val number: Int = 0,
    val tracks: List<Track> = emptyList(),
    /** Parallel to tracks (mixes): a recommendation, not heard before. */
    val fresh: List<Boolean> = emptyList(),
    /** mix: its artists (the lead one first), for the title line. */
    val artists: List<String> = emptyList(),
    /** mix: the lead artist's most streamed song (a Spotify radio seed when it is one). */
    val seedTrackId: String = "",
    /** mix: the lead artist's id (a ListenBrainz seed when it is an MBID). */
    val seedArtistId: String = "",
    /** The streams behind the list (the "why" line). */
    val streams: Int = 0,
    /** Local day it was built for ([Listen.dayNumber]). */
    val day: Long = 0,
)

/**
 * Akıllı listeler ("Senin için listeler"): playlists computed on this device from what the user really listened to
 * ([ListenStats]: this device's plays and the imported Spotify history) and their liked songs. The pure part (port
 * of app/SmartLists): [build] turns a copy of the history into lists, [mixFresh] blends recommendations into a daily
 * mix. The app side ([SmartListsService]: when to rebuild, the recommendations, the cache file) calls them on a
 * worker.
 *
 * The lists (a list with fewer songs than its minimum is left out):
 * - month        Bu ayın favorileri        streamed at least twice in the last 30 days, most streamed first
 * - best         Tüm zamanların en iyileri every stream counts, recent ones more (half-life one year)
 * - rediscover   Tekrar keşfet             streamed 4+ times, not in the last 90 days (a new pick every day)
 * - discoveries  Yeni keşiflerin           first heard in the last 30 days and streamed again since
 * - forgotten    Unutulan beğeniler        liked songs streamed at most once and not in the last 60 days
 * - mix:1..3     Günün karışımı N          an artist the user listens to now + the artists they play it with (the
 *                                          same listening sessions), their familiar songs in a daily order; fresh
 *                                          songs are added by the app ([mixFresh])
 * - year:<Y>     <Y> en iyileri            the most streamed songs of a local calendar year
 *
 * Only streams count (ListenStats' rule: >= 30 s, or half of a short track). Podcast episodes, radio stations and
 * unnamed rows never appear. Songs are the history's distinct tracks (merged by name + first artist across
 * sources), so a song shows up once. Orders that are not a ranking are shuffled with a seed of the local day: a list
 * is stable for the day and changes the next. Thread-agnostic (pure).
 */
object SmartLists {
    enum class Kind { Month, Best, Rediscover, Discoveries, Forgotten, Mix, Year }

    // Minimum songs for a list to be shown, and the most it holds.
    const val MIN_SONGS = 8
    const val MIN_DISCOVERIES = 5
    const val MAX_SONGS = 50
    const val MAX_BEST = 100
    const val MAX_MIXES = 3
    /** Familiar songs a mix takes at most. */
    const val MIX_FAMILIAR = 30
    /** A mix needs at least this many. */
    const val MIX_MIN_FAMILIAR = 10
    /** Recommendations mixFresh() blends in at most. */
    const val MIX_FRESH = 15
    /** Newest years that get a list. */
    const val MAX_YEARS = 6
    const val MIN_YEAR_SONGS = 10

    private const val DAY = 86_400L
    /** A pause longer than this starts a new listening session. */
    private const val SESSION_GAP_S = 30 * 60L
    /** Sessions of the last two years relate artists. */
    private const val MIX_WINDOW_DAYS = 730L
    /** An artist relates to the last few other artists of its session. */
    private const val SESSION_ARTISTS = 5
    /** Artists a mix adds to its lead artist. */
    private const val MIX_NEIGHBORS = 4
    /** Familiar songs of one artist at most. */
    private const val MIX_PER_ARTIST = 12

    class Input(
        /** The history's distinct tracks ([ListenStats.tracksCopy]). */
        val tracks: List<ListenTrack> = emptyList(),
        /** Oldest first ([ListenStats.playsCopy]). */
        val plays: PlayList = PlayList(0),
        /** The user's liked songs (Spotify's or the local library's). */
        val liked: List<Track> = emptyList(),
        /** Unix seconds. */
        val now: Long = 0,
        /** Local wall clock (days, years); null = UTC. */
        val clock: LocalClock? = null,
    )

    private fun excludedId(id: String) = id.startsWith("podcast:") || id.startsWith("radio:")
    private fun excluded(t: ListenTrack) = t.name.isEmpty() || excludedId(t.id)

    /** Seed of a list for a day (stable within the day, different per list). */
    fun seedFor(listId: String, day: Long): Long = fnv64(listId, fnv64(day.toString()))

    /** Folded name + first artist key of a catalog track (ListenStats' song identity). */
    fun songKey(t: Track): String = ListenKeys.of(ListenStats.fromCatalog(t)).track

    /** Weighted pick of [k] indexes without replacement (Efraimidis-Spirakis), in the order picked. */
    private fun weightedPick(weights: DoubleArray, k: Int, seed: Long): IntArray {
        val rng = Mt19937(seed)
        val keys = DoubleArray(weights.size)
        for (i in weights.indices) keys[i] = rng.nextUnit().pow(1.0 / max(weights[i], 1e-9))
        val order = weights.indices.sortedByDescending { keys[it] }
        return order.take(k).toIntArray()
    }

    private fun <T> seededShuffle(v: MutableList<T>, seed: Long) {
        val rng = Mt19937(seed)
        for (i in v.size downTo 2) {
            val j = rng.nextBelow(i.toLong()).toInt()
            val t = v[i - 1]
            v[i - 1] = v[j]
            v[j] = t
        }
    }

    /** Reorders so that the same artist rarely plays twice in a row (a greedy pass; keeps the order otherwise). */
    private fun spreadArtists(order: IntArray, artistOf: IntArray) {
        for (i in 1 until order.size) {
            if (artistOf[order[i]] != artistOf[order[i - 1]]) continue
            for (j in i + 1 until order.size)
                if (artistOf[order[j]] != artistOf[order[i - 1]]) {
                    val t = order[i]
                    order[i] = order[j]
                    order[j] = t
                    break
                }
        }
    }

    private fun minSongs(kind: Kind) = when (kind) {
        Kind.Discoveries -> MIN_DISCOVERIES
        Kind.Mix -> MIX_MIN_FAMILIAR
        Kind.Year -> MIN_YEAR_SONGS
        else -> MIN_SONGS
    }

    private fun maxSongs(kind: Kind) = when (kind) {
        Kind.Best -> MAX_BEST
        Kind.Mix -> MIX_FAMILIAR + MIX_FRESH
        else -> MAX_SONGS
    }

    /** Open-addressing Long -> Int counter (artist pairs; avoids a boxed map entry per pair). */
    private class PairCounter {
        var keys = LongArray(1024)
        var values = IntArray(1024)
        var used = BooleanArray(1024)
        var size = 0

        fun increment(key: Long) {
            if (size * 2 >= keys.size) rehash()
            var i = slot(key, keys.size)
            while (used[i] && keys[i] != key) i = (i + 1) and (keys.size - 1)
            if (!used[i]) {
                used[i] = true
                keys[i] = key
                size++
            }
            values[i]++
        }

        private fun slot(key: Long, n: Int): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 32)
            return (h.toInt()) and (n - 1)
        }

        private fun rehash() {
            val ok = keys
            val ov = values
            val ou = used
            keys = LongArray(ok.size * 2)
            values = IntArray(ok.size * 2)
            used = BooleanArray(ok.size * 2)
            for (j in ok.indices) {
                if (!ou[j]) continue
                var i = slot(ok[j], keys.size)
                while (used[i]) i = (i + 1) and (keys.size - 1)
                used[i] = true
                keys[i] = ok[j]
                values[i] = ov[j]
            }
        }
    }

    /**
     * Every list the history supports right now, in display order (mixes first, the years last). Songs are not
     * filtered by the blocklist here: the caller does that and then calls [trim].
     */
    fun build(input: Input): List<SmartList> {
        val clock = input.clock
        fun local(unix: Long) = clock?.toLocal(unix) ?: unix
        val now = input.now
        val today = Listen.dayNumber(local(now))
        val tracks = input.tracks
        val n = tracks.size

        // --- Per song and per (first) artist.
        val streams = IntArray(n)
        val recentStreams = IntArray(n) // streams in the last 30 days
        val firstAt = LongArray(n) { Long.MAX_VALUE }
        val lastAt = LongArray(n)
        val score = DoubleArray(n) // every stream, recent ones worth more
        val artistOf = IntArray(n)
        val keys = arrayOfNulls<String>(n)
        val artistByKey = HashMap<String, Int>()
        val trackByKey = HashMap<String, Int>()
        val artistName = ArrayList<String>()
        val artistId = ArrayList<String>()
        for (i in 0 until n) {
            val k = ListenKeys.of(tracks[i])
            keys[i] = k.track
            trackByKey.putIfAbsent(k.track, i)
            var a = artistByKey[k.firstArtist]
            if (a == null) {
                a = artistName.size
                artistByKey[k.firstArtist] = a
                val ars = tracks[i].artists
                artistName.add(if (ars.isEmpty()) "" else ars[0].name)
                artistId.add(if (ars.isEmpty()) "" else ars[0].id)
            } else if (artistId[a].isEmpty() && tracks[i].artists.isNotEmpty()) {
                artistId[a] = tracks[i].artists[0].id
            }
            artistOf[i] = a
        }
        val artists = artistName.size
        val artistStreams = IntArray(artists)
        val artistRecent = IntArray(artists) // recent: last 90 days

        // Streams: song totals, artist totals, year totals and the sessions that relate artists.
        val cooc = PairCounter() // artist pair (low << 32 | high) -> times heard together
        val yearStreams = HashMap<Int, IntArray>() // year -> streams per song
        val session = ArrayList<Int>() // the last distinct artists of the running session
        var sessionEnd = Long.MIN_VALUE
        val plays = input.plays
        for (pi in 0 until plays.size) {
            val track = plays.track(pi)
            if (track < 0 || track >= n) continue
            val t = tracks[track]
            val startedAt = plays.startedAt(pi)
            val ms = plays.listenedMs(pi)
            if (excluded(t) || !Listen.counts(ms, t.durationMs)) continue
            val age = max(0L, now - startedAt)
            streams[track]++
            if (age <= 30 * DAY) recentStreams[track]++
            firstAt[track] = min(firstAt[track], startedAt)
            lastAt[track] = max(lastAt[track], startedAt)
            score[track] += 0.3 + 0.7 * 2.0.pow(-age.toDouble() / (365.0 * DAY))
            val ar = artistOf[track]
            artistStreams[ar]++
            if (age <= 90 * DAY) artistRecent[ar]++
            val year = Listen.packedYear(Listen.civilPacked(Listen.dayNumber(local(startedAt))))
            yearStreams.getOrPut(year) { IntArray(n) }[track]++
            if (age > MIX_WINDOW_DAYS * DAY || artistName[ar].isEmpty()) continue
            if (sessionEnd != Long.MIN_VALUE && startedAt > sessionEnd + SESSION_GAP_S) session.clear()
            sessionEnd = max(sessionEnd, startedAt + ms / 1000)
            session.remove(ar)
            for (b in session) {
                val key = (min(ar, b).toLong() shl 32) or max(ar, b).toLong()
                cooc.increment(key)
            }
            session.add(0, ar)
            if (session.size > SESSION_ARTISTS) session.removeAt(session.size - 1)
        }

        val streamed = ArrayList<Int>() // songs with a stream
        for (i in 0 until n) if (streams[i] > 0) streamed.add(i)

        /** Total order: the same input always gives the same list. */
        fun ranked(keep: (Int) -> Boolean, before: (Int, Int) -> Boolean): List<Int> =
            streamed.filter(keep).sortedWith { x, y ->
                when {
                    before(x, y) -> -1
                    before(y, x) -> 1
                    else -> keys[x]!!.compareTo(keys[y]!!)
                }
            }

        val out = ArrayList<SmartList>()

        // --- Günün karışımı: a lead artist the user plays now + the artists they play it with.
        run {
            val leads = (0 until artists).filter { artistStreams[it] >= 5 && artistName[it].isNotEmpty() }
                .sortedWith { x, y ->
                    val sx = artistRecent[x] * 2 + artistStreams[x]
                    val sy = artistRecent[y] * 2 + artistStreams[y]
                    if (sx != sy) sy.compareTo(sx) else artistName[x].compareTo(artistName[y])
                }
            val used = BooleanArray(artists)
            val related = arrayOfNulls<ArrayList<Pair<Double, Int>>>(artists)
            for (slot in cooc.keys.indices) {
                if (!cooc.used[slot]) continue
                val count = cooc.values[slot]
                if (count < 2) continue
                val key = cooc.keys[slot]
                val a = (key ushr 32).toInt()
                val b = (key and 0xFFFFFFFFL).toInt()
                val s = count / sqrt(max(1, artistStreams[a]).toDouble() * max(1, artistStreams[b]))
                (related[a] ?: ArrayList<Pair<Double, Int>>().also { related[a] = it }).add(s to b)
                (related[b] ?: ArrayList<Pair<Double, Int>>().also { related[b] = it }).add(s to a)
            }
            var number = 0
            for (lead in leads) {
                if (out.size >= MAX_MIXES || used[lead]) continue
                val rel = (related[lead] ?: emptyList<Pair<Double, Int>>()).sortedWith { x, y ->
                    if (x.first != y.first) y.first.compareTo(x.first) else artistName[x.second].compareTo(artistName[y.second])
                }
                val members = arrayListOf(lead)
                for ((_, b) in rel)
                    if (!used[b] && members.size < 1 + MIX_NEIGHBORS && artistName[b].isNotEmpty()) members.add(b)
                // Familiar songs of the mix's artists, the best liked ones more likely, at most a few per artist.
                val memberSet = members.toHashSet()
                val cand = streamed.filter { artistOf[it] in memberSet }
                if (cand.size < MIX_MIN_FAMILIAR) continue
                val id = "mix:" + (number + 1)
                val w = DoubleArray(cand.size) { score[cand[it]] }
                val picked = ArrayList<Int>()
                val perArtist = HashMap<Int, Int>()
                for (k in weightedPick(w, cand.size, seedFor(id, today))) {
                    val i = cand[k]
                    val c = perArtist[artistOf[i]] ?: 0
                    if (c >= MIX_PER_ARTIST) continue
                    perArtist[artistOf[i]] = c + 1
                    picked.add(i)
                    if (picked.size >= MIX_FAMILIAR) break
                }
                if (picked.size < MIX_MIN_FAMILIAR) continue
                val order = picked.toIntArray()
                spreadArtists(order, artistOf)
                var mixStreams = 0
                val mixTracks = ArrayList<Track>(order.size)
                for (i in order) {
                    mixTracks.add(ListenStats.toCatalog(tracks[i]))
                    mixStreams += streams[i]
                }
                for (a in members) used[a] = true
                var top = -1
                for (i in cand) if (artistOf[i] == lead && (top < 0 || streams[i] > streams[top])) top = i
                number++
                out.add(
                    SmartList(
                        id = id, kind = Kind.Mix, number = number, tracks = mixTracks,
                        fresh = List(mixTracks.size) { false },
                        artists = members.map { artistName[it] },
                        seedTrackId = if (top >= 0) tracks[top].id else "",
                        seedArtistId = artistId[lead], streams = mixStreams, day = today,
                    ),
                )
            }
        }

        fun ranking(id: String, kind: Kind, number: Int, songs: List<Int>, cap: Int, streamsOf: (Int) -> Int) {
            val picked = songs.take(cap)
            val l = SmartList(
                id = id, kind = kind, number = number, tracks = picked.map { ListenStats.toCatalog(tracks[it]) },
                fresh = List(picked.size) { false }, streams = picked.sumOf(streamsOf), day = today,
            )
            if (l.tracks.size >= minSongs(kind)) out.add(l)
        }

        // --- Bu ayın favorileri.
        ranking(
            "month", Kind.Month, 0,
            ranked({ recentStreams[it] >= 2 }) { x, y ->
                if (recentStreams[x] != recentStreams[y]) recentStreams[x] > recentStreams[y] else lastAt[x] > lastAt[y]
            },
            MAX_SONGS,
        ) { recentStreams[it] }

        // --- Yeni keşiflerin.
        run {
            val since = now - 30 * DAY
            ranking(
                "discoveries", Kind.Discoveries, 0,
                ranked({ firstAt[it] >= since && streams[it] >= 2 }) { x, y ->
                    if (streams[x] != streams[y]) streams[x] > streams[y] else firstAt[x] > firstAt[y]
                },
                MAX_SONGS,
            ) { streams[it] }
        }

        // --- Tekrar keşfet: a daily pick among the old favorites.
        run {
            val before = now - 90 * DAY
            val cand = ranked({ streams[it] >= 4 && lastAt[it] < before }) { x, y ->
                if (streams[x] != streams[y]) streams[x] > streams[y] else lastAt[x] > lastAt[y]
            }.take(2 * MAX_SONGS)
            val w = DoubleArray(cand.size) { streams[cand[it]].toDouble() }
            val picked = weightedPick(w, MAX_SONGS, seedFor("rediscover", today)).map { cand[it] }
            ranking("rediscover", Kind.Rediscover, 0, picked, MAX_SONGS) { streams[it] }
        }

        // --- Unutulan beğeniler: liked, hardly ever streamed, not lately.
        run {
            val seen = HashSet<String>()
            val cand = ArrayList<Track>()
            for (t in input.liked) {
                if (t.name.isEmpty() || !t.playable || excludedId(t.id)) continue
                val k = songKey(t)
                if (!seen.add(k)) continue
                val i = trackByKey[k]
                if (i != null) {
                    if (streams[i] > 1 || (streams[i] == 1 && now - lastAt[i] < 60 * DAY)) continue
                }
                cand.add(t)
            }
            seededShuffle(cand, seedFor("forgotten", today))
            val picked = cand.take(MAX_SONGS)
            // streams: liked songs waiting to be played
            val l = SmartList("forgotten", Kind.Forgotten, 0, picked, List(picked.size) { false }, streams = cand.size, day = today)
            if (l.tracks.size >= minSongs(Kind.Forgotten)) out.add(l)
        }

        // --- Tüm zamanların en iyileri.
        ranking("best", Kind.Best, 0, ranked({ streams[it] >= 3 }) { x, y -> score[x] > score[y] }, MAX_BEST) { streams[it] }

        // --- The years (newest first).
        run {
            var made = 0
            for (y in yearStreams.keys.sortedDescending()) {
                if (made >= MAX_YEARS) break
                val counts = yearStreams.getValue(y)
                val songs = ArrayList<Int>()
                for (i in 0 until n) if (counts[i] > 0) songs.add(i)
                if (songs.size < MIN_YEAR_SONGS) continue
                songs.sortWith { a, b ->
                    when {
                        a == b -> 0
                        counts[a] != counts[b] -> counts[b].compareTo(counts[a])
                        score[a] != score[b] -> score[b].compareTo(score[a])
                        else -> keys[a]!!.compareTo(keys[b]!!)
                    }
                }
                val picked = songs.take(MAX_SONGS)
                out.add(
                    SmartList(
                        id = "year:$y", kind = Kind.Year, number = y, tracks = picked.map { ListenStats.toCatalog(tracks[it]) },
                        fresh = List(picked.size) { false }, streams = picked.sumOf { counts[it] }, day = today,
                    ),
                )
                made++
            }
        }
        return out
    }

    /**
     * Adds up to [MIX_FRESH] [candidates] to a mix: songs the history doesn't know and the mix doesn't hold (by name
     * + first artist), one after every two familiar songs, in a seeded order. [knownKeys] = [songKey] / the
     * ListenKeys track key of every song in the history (the caller builds it once).
     */
    fun mixFresh(mix: SmartList, candidates: List<Track>, knownKeys: Collection<String>): SmartList {
        val known = HashSet<String>(knownKeys)
        for (t in mix.tracks) known.add(songKey(t))
        val fresh = ArrayList<Track>()
        for (c in candidates) {
            if (fresh.size >= MIX_FRESH) break
            if (c.name.isEmpty() || !c.playable || excludedId(c.id)) continue
            if (!known.add(songKey(c))) continue
            fresh.add(c)
        }
        if (fresh.isEmpty()) return mix
        seededShuffle(fresh, seedFor(mix.id + "#fresh", mix.day))
        val tracks = ArrayList<Track>()
        val flags = ArrayList<Boolean>()
        var f = 0
        for (i in mix.tracks.indices) {
            tracks.add(mix.tracks[i])
            flags.add(i < mix.fresh.size && mix.fresh[i])
            if (i % 2 == 1 && f < fresh.size) { // one new song after every two familiar ones
                tracks.add(fresh[f++])
                flags.add(true)
            }
        }
        while (f < fresh.size && tracks.size < maxSongs(mix.kind)) {
            tracks.add(fresh[f++])
            flags.add(true)
        }
        return mix.copy(tracks = tracks, fresh = flags)
    }

    /**
     * Caps a list after the caller dropped songs (blocklist): the capped list, or null when it fell below its
     * minimum (hide it).
     */
    fun trim(list: SmartList): SmartList? {
        val cap = maxSongs(list.kind)
        val tracks = list.tracks.take(cap)
        val fresh = List(tracks.size) { i -> i < list.fresh.size && list.fresh[i] }
        val familiar = fresh.count { !it }
        val enough = (if (list.kind == Kind.Mix) familiar else tracks.size) >= minSongs(list.kind)
        return if (enough) list.copy(tracks = tracks, fresh = fresh) else null
    }

    /** The list minus the songs [blocked] says no to, capped; null when it fell below its minimum. */
    fun withoutBlocked(list: SmartList, blocked: (Track) -> Boolean): SmartList? {
        val tracks = ArrayList<Track>()
        val fresh = ArrayList<Boolean>()
        for (i in list.tracks.indices) {
            if (blocked(list.tracks[i])) continue
            tracks.add(list.tracks[i])
            fresh.add(i < list.fresh.size && list.fresh[i])
        }
        return trim(list.copy(tracks = tracks, fresh = fresh))
    }
}
