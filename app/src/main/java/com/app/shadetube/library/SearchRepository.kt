package com.app.shadetube.library

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.Artist
import com.app.shadetube.catalog.SearchResults
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Paths
import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isArray
import com.app.shadetube.core.isObject
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.stringOrNull
import com.app.shadetube.links.Link
import com.app.shadetube.links.LinkIcon
import com.app.shadetube.links.LinkKind
import com.app.shadetube.links.LinkOpener
import com.app.shadetube.links.LinkVerb
import com.app.shadetube.links.Links
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Son aramalar": the last queries the user really searched for (submitted, or a result opened from them), newest
 * first, max [MAX], case-insensitively unique (port of the C++ RecentSearches). recent-searches.json — a few hundred
 * bytes, `{"v":1,"queries":[...]}`, read once on first use and rewritten (write-then-rename) on every change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentSearches(private val file: () -> File = { Paths.file(FILE_NAME) }) {
    private val mutex = Mutex()
    private var loaded = false
    private val _items = MutableStateFlow<List<String>>(emptyList())
    private val io = AppDispatchers.io.limitedParallelism(1)

    /** Empty until [load] (or any change) ran. */
    val items: StateFlow<List<String>> = _items.asStateFlow()

    suspend fun load(): List<String> = locked { _items.value }

    suspend fun record(query: String) = locked {
        val q = normalize(query)
        if (q.isEmpty()) return@locked
        _items.value = (listOf(q) + _items.value.filter { !sameQuery(it, q) }).take(MAX)
        save()
    }

    suspend fun remove(query: String) = locked {
        val before = _items.value
        val after = before.filter { !sameQuery(it, query) }
        if (after.size != before.size) {
            _items.value = after
            save()
        }
    }

    suspend fun clear() = locked {
        _items.value = emptyList()
        save()
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(io) {
        mutex.withLock {
            if (!loaded) {
                loaded = true
                _items.value = readFile()
            }
            block()
        }
    }

    private fun readFile(): List<String> {
        val f = file()
        if (!f.exists()) return emptyList()
        val j = runCatching { f.readText() }.getOrNull()?.let { parseJsonOrNull(it) }
        if (!j.isObject || !j["queries"].isArray) return emptyList()
        val out = ArrayList<String>()
        for (q in j["queries"].arr) {
            val s = normalize(q.takeIf { it is kotlinx.serialization.json.JsonPrimitive && it.isString }?.stringOrNull ?: continue)
            if (s.isNotEmpty() && out.size < MAX && out.none { sameQuery(it, s) }) out.add(s)
        }
        return out
    }

    private fun save() {
        Files.writeJson(file(), jsonOf("v" to 1, "queries" to _items.value))
    }

    companion object {
        const val FILE_NAME = "recent-searches.json"
        const val MAX = 10
        private const val MAX_CHARS = 120

        /** Trimmed, inner whitespace runs collapsed, capped (a pasted paragraph must not become a chip). */
        fun normalize(s: String): String {
            val out = StringBuilder()
            for (ch in s) {
                if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                } else {
                    out.append(ch)
                }
            }
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
            if (out.length > MAX_CHARS) out.setLength(MAX_CHARS)
            return out.toString()
        }

        /**
         * Case-insensitive, with Turkish İ treated as I/i (Windows' ordinal table leaves U+0130 alone); ı stays its own
         * letter.
         */
        fun sameQuery(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            for (i in a.indices) if (key(a[i]) != key(b[i])) return false
            return true
        }

        private fun key(c: Char): Char = when (c) {
            'İ' -> 'I'
            'ı' -> c
            else -> c.uppercaseChar()
        }
    }
}

/** What the search box text is (C++ SearchPage onChange). */
sealed interface SearchInput {
    /** Empty: "Son aramalar" above the genre tiles. */
    data object Browse : SearchInput

    /** One character: too short to search; keep what is shown. */
    data object TooShort : SearchInput

    /** Search [query] after [SearchRepository.DEBOUNCE_MS] without further typing (MusicBrainz allows 1 request/s). */
    data class Query(val query: String) : SearchInput

    /** A pasted / typed link: offer to open it instead of searching for a URL. */
    data class LinkFound(val hint: LinkHint) : SearchInput
}

/**
 * The row under the search box while its text is a link (app/Links): what the link is and what opening it does.
 * A Spotify link while logged out offers the connect screen instead; an unsupported link only explains itself.
 */
data class LinkHint(
    val link: Link,
    val state: State,
    /** "Şarkıyı çal", "Albümü aç"... (null when unsupported). */
    val verb: LinkVerb?,
    val icon: LinkIcon,
) {
    enum class State {
        /** "Bu bağlantı açılamıyor" — "Şarkı, albüm, çalma listesi ve sanatçı bağlantıları desteklenir." Not focusable. */
        Unsupported,

        /** "Spotify'a bağlan" — "{label} · Açmak için Spotify hesabınla bağlan"; activating shows the connect screen. */
        Connect,

        /** The verb + the link label; Enter / click opens it (links.LinkOpener). */
        Open,
    }

    val activatable: Boolean get() = state != State.Unsupported
}

/** What Enter in the search box does. */
sealed interface SearchSubmit {
    data object Nothing : SearchSubmit

    /** Open the link (LinkOpener.open with AppGraph's lookups); the query is not recorded. */
    data class OpenLink(val link: Link) : SearchSubmit

    /** A Spotify link while logged out: show the connect screen. */
    data class Connect(val link: Link) : SearchSubmit

    /** A real search (recorded in "Son aramalar"). */
    data class Search(val query: String) : SearchSubmit
}

enum class SearchTab { All, Tracks, Albums, Artists }

/** Genre tiles ("Türlere göz at", MUSICBRAINZ ETİKETLERİ): the UI maps [key] to its title. */
data class Genre(val key: Key, val tag: String, val color: Int) {
    enum class Key {
        TurkishPop, AnatolianRock, Arabesque, TurkishFolk, Rock, HipHop, Electronic, Jazz, Classical, Metal, Indie,
        RnB, LoFi, Ambient, Soul, Blues
    }
}

data class TagResults(val artists: List<Artist>, val albums: List<Album>)

/**
 * The search page's data (port of the non-UI part of SearchPage in BrowsePages.cpp): Spotify search when logged in,
 * MusicBrainz otherwise; links in the box; "Son aramalar"; genre (MusicBrainz tag) browsing.
 */
class SearchRepository(
    private val source: CatalogSource,
    val recent: RecentSearches = RecentSearches(),
) {
    fun linkHint(link: Link): LinkHint {
        val unsupported = link.kind == LinkKind.Unsupported
        val connect = LinkOpener.needsSpotify(link, source.loggedIn)
        val state = when {
            unsupported -> LinkHint.State.Unsupported
            connect -> LinkHint.State.Connect
            else -> LinkHint.State.Open
        }
        return LinkHint(link, state, LinkOpener.verb(link), LinkOpener.icon(link))
    }

    fun classify(text: String): SearchInput {
        val link = Links.parse(text)
        if (link.isLink) return SearchInput.LinkFound(linkHint(link))
        if (text.isEmpty()) return SearchInput.Browse
        if (text.length < 2) return SearchInput.TooShort
        return SearchInput.Query(text)
    }

    /** Enter = a real search (typing alone never records); a link is opened instead. */
    suspend fun submit(text: String): SearchSubmit {
        val link = Links.parse(text)
        if (link.isLink) {
            return when (linkHint(link).state) {
                LinkHint.State.Unsupported -> SearchSubmit.Nothing
                LinkHint.State.Connect -> SearchSubmit.Connect(link)
                LinkHint.State.Open -> SearchSubmit.OpenLink(link)
            }
        }
        if (text.isEmpty()) return SearchSubmit.Nothing
        recent.record(text)
        return SearchSubmit.Search(text)
    }

    /** The current query, remembered once the user acts on its results (opens a card / plays a track). */
    suspend fun remember(query: String) = recent.record(query)

    /** Spotify when logged in (captured now), else MusicBrainz. Throws on failure (error panel + retry). */
    suspend fun search(query: String): SearchResults = source.search(source.activeApi(), query, RESULT_LIMIT)

    /** A genre tile: MusicBrainz artists and albums with that tag (the open catalog, logged in or not). */
    suspend fun tagSearch(tag: String): TagResults {
        val q = "tag:\"$tag\""
        val artists = source.openCatalog.searchArtists(q, 18)
        val albums = source.openCatalog.searchAlbums("$q AND primarytype:album", 24)
        return TagResults(artists, albums)
    }

    companion object {
        /** Search 450 ms after the last keystroke (MusicBrainz allows 1 request/second). */
        const val DEBOUNCE_MS = 450L
        const val RESULT_LIMIT = 12

        /** Songs on the "Tümü" tab. */
        const val ALL_TAB_TRACKS = 5

        val genres: List<Genre> = listOf(
            Genre(Genre.Key.TurkishPop, "turkish pop", 0x3B2A1E),
            Genre(Genre.Key.AnatolianRock, "anatolian rock", 0x1F2A22),
            Genre(Genre.Key.Arabesque, "arabesque", 0x2E1D1D),
            Genre(Genre.Key.TurkishFolk, "turkish folk", 0x33261B),
            Genre(Genre.Key.Rock, "rock", 0x1C2433),
            Genre(Genre.Key.HipHop, "hip hop", 0x2A1E2A),
            Genre(Genre.Key.Electronic, "electronic", 0x1E2B2B),
            Genre(Genre.Key.Jazz, "jazz", 0x26241C),
            Genre(Genre.Key.Classical, "classical", 0x2B2433),
            Genre(Genre.Key.Metal, "metal", 0x1B1B1B),
            Genre(Genre.Key.Indie, "indie", 0x23301F),
            Genre(Genre.Key.RnB, "rnb", 0x331E27),
            Genre(Genre.Key.LoFi, "lo-fi", 0x1F2630),
            Genre(Genre.Key.Ambient, "ambient", 0x1E2A30),
            Genre(Genre.Key.Soul, "soul", 0x30241C),
            Genre(Genre.Key.Blues, "blues", 0x1C2233),
        )
    }
}
