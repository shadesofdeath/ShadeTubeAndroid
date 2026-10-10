package com.app.shadetube.library

import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.int
import com.app.shadetube.core.parseJsonOrNull
import com.app.shadetube.core.stringOrNull
import com.app.shadetube.links.LinkKind
import com.app.shadetube.links.LinkVerb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun recent(name: String = "recent-searches.json") = RecentSearches { File(tmp.root, name) }

    @Test
    fun normalizeCollapsesAndCaps() {
        assertEquals("a b c", RecentSearches.normalize("  a \t\n b   c  "))
        assertEquals("", RecentSearches.normalize("   "))
        assertEquals(120, RecentSearches.normalize("x".repeat(500)).length)
    }

    @Test
    fun sameQueryIsCaseInsensitiveWithTurkishCapitalI() {
        assertTrue(RecentSearches.sameQuery("Tarkan", "TARKAN"))
        assertTrue(RecentSearches.sameQuery("İstanbul", "istanbul"))
        assertTrue(RecentSearches.sameQuery("İstanbul", "ISTANBUL"))
        assertFalse(RecentSearches.sameQuery("ılık", "ILIK")) // ı stays its own letter
        assertFalse(RecentSearches.sameQuery("ab", "abc"))
    }

    @Test
    fun recordKeepsTenNewestUniqueAndPersists() = runBlocking {
        val r = recent()
        for (i in 1..12) r.record("q$i")
        r.record("  Q5 ")
        val items = r.items.value
        assertEquals(RecentSearches.MAX, items.size)
        assertEquals("Q5", items[0])
        assertEquals("q12", items[1])
        assertFalse(items.any { it == "q5" })
        r.record("   ") // empty: ignored
        assertEquals(10, r.items.value.size)

        val j = parseJsonOrNull(File(tmp.root, "recent-searches.json").readText())!!
        assertEquals(1, j.int("v"))
        assertEquals("Q5", j["queries"].arr[0].stringOrNull)

        val again = recent()
        assertEquals(items, again.load())
        again.remove("q5") // case-insensitive
        assertFalse(again.items.value.any { it == "Q5" })
        again.clear()
        assertTrue(recent().load().isEmpty())
    }

    @Test
    fun loadSkipsJunk() = runBlocking {
        File(tmp.root, "r.json").writeText("""{"v":1,"queries":["a"," A ",3,"",null,"b  c"]}""")
        assertEquals(listOf("a", "b c"), recent("r.json").load())
        File(tmp.root, "bad.json").writeText("""{"queries":"x"}""")
        assertTrue(recent("bad.json").load().isEmpty())
    }

    @Test
    fun classifyAndSubmit() = runBlocking {
        val acc = FakeAccount(loggedIn = false)
        val repo = SearchRepository(CatalogSource(acc, FakeOpenCatalog()), recent())
        assertEquals(SearchInput.Browse, repo.classify(""))
        assertEquals(SearchInput.TooShort, repo.classify("a"))
        assertEquals(SearchInput.Query("ab"), repo.classify("ab"))

        val sp = repo.classify("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy") as SearchInput.LinkFound
        assertEquals(LinkHint.State.Connect, sp.hint.state) // logged out: connect first
        assertEquals(LinkVerb.OpenAlbum, sp.hint.verb)
        val unsupported = repo.classify("https://open.spotify.com/show/4aawyAB9vmqN3uQ7FjRGTy") as SearchInput.LinkFound
        assertEquals(LinkHint.State.Unsupported, unsupported.hint.state)
        assertFalse(unsupported.hint.activatable)

        assertTrue(repo.submit("spotify:album:4aawyAB9vmqN3uQ7FjRGTy") is SearchSubmit.Connect)
        acc.login()
        val open = repo.submit("spotify:album:4aawyAB9vmqN3uQ7FjRGTy") as SearchSubmit.OpenLink
        assertEquals(LinkKind.SpotifyAlbum, open.link.kind)
        assertTrue(repo.recent.items.value.isEmpty()) // links are not recorded
        assertEquals(SearchSubmit.Search("tarkan"), repo.submit("tarkan"))
        assertEquals(listOf("tarkan"), repo.recent.items.value)
        assertEquals(SearchSubmit.Nothing, repo.submit(""))
    }

    @Test
    fun searchUsesTheActiveSourceAndTagsUseMusicBrainz() = runBlocking {
        val acc = FakeAccount(loggedIn = true)
        val mb = FakeOpenCatalog()
        val repo = SearchRepository(CatalogSource(acc, mb), recent())
        repo.search("x")
        assertEquals(1, acc.searchCalls)
        val t = repo.tagSearch("anatolian rock")
        assertEquals(1, t.artists.size)
        assertEquals(listOf("artists:tag:\"anatolian rock\":18", "albums:tag:\"anatolian rock\" AND primarytype:album:24"), mb.searches)
        assertEquals(16, SearchRepository.genres.size)
        assertEquals(0x3B2A1E, SearchRepository.genres[0].color)
    }
}
