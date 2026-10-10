package com.app.shadetube.musicbrainz

import com.app.shadetube.catalog.Album
import com.app.shadetube.catalog.ArtistRef
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.coverArt
import com.app.shadetube.catalog.pick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Port of tests/musicbrainz offlineTests(): parsing against captured fixtures. */
class MbParseTest {
    private fun fixture(name: String): JsonElement {
        val candidates = listOf(
            File("src/test/java/com/app/shadetube/musicbrainz/fixtures/$name"),
            File("app/src/test/java/com/app/shadetube/musicbrainz/fixtures/$name"),
        )
        val f = candidates.first { it.exists() }
        return Json.parseToJsonElement(f.readText())
    }

    @Test
    fun models() {
        val imgs = coverArt("abc")
        assertEquals(3, imgs.size)
        assertEquals(500, imgs.pick(300)!!.width)
        assertEquals(1200, imgs.pick(5000)!!.width)
        assertNull(emptyList<com.app.shadetube.catalog.Image>().pick(100))
        assertTrue(coverArt("").isEmpty())
        assertEquals("A, B", Track(artists = listOf(ArtistRef("1", "A"), ArtistRef("2", "B"))).artistLine)
    }

    @Test
    fun queries() {
        assertTrue(MbParse.hasFieldPrefix("tag:\"anadolu rock\""))
        assertTrue(MbParse.hasFieldPrefix("tag:rock"))
        assertTrue(MbParse.hasFieldPrefix("foo AND country:TR"))
        assertFalse(MbParse.hasFieldPrefix("Tarkan"))
        assertFalse(MbParse.hasFieldPrefix("Re:Cognition")) // not a known field
        assertEquals("tag:\"anadolu rock\"", MbParse.releaseGroupQuery("tag:\"anadolu rock\""))
        assertEquals("AC\\/DC \\(live\\)", MbParse.luceneEscape("AC/DC (live)"))
        assertEquals(
            "(recording:(tarkan Şımarık) OR artist:(tarkan Şımarık)) AND video:false",
            MbParse.recordingQuery("Tarkan Şımarık"),
        )
        assertTrue(MbParse.artistQuery("  ").isEmpty())
        assertEquals(
            "artist:\"sezen aksu\"^3 OR artist:(sezen aksu) OR alias:(sezen aksu)",
            MbParse.artistQuery(" Sezen AKSU "),
        )
    }

    @Test
    fun artistSearchLookupWikidata() {
        val artists = MbParse.parseArtistSearch(fixture("artist_search.json"), 10)
        assertTrue(artists.isNotEmpty())
        assertEquals("Tarkan", artists[0].name)
        assertEquals("TR", artists[0].country)
        assertEquals("1972", artists[0].beginYear)
        assertEquals("pop", artists[0].tags.firstOrNull())

        val lookup = fixture("artist_lookup.json")
        val a = MbParse.parseArtist(lookup)
        assertEquals("4ec2451d-ed0c-4273-b683-4c1312df25fd", a.id)
        assertEquals("Person", a.type)
        assertEquals("Q485771", MbParse.wikidataId(lookup))
        val file = MbParse.wikidataImageFile(fixture("wikidata_entity.json"))
        assertEquals("Tarkan (9).jpg", file)
        val photos = MbParse.commonsImages(file)
        assertEquals(3, photos.size)
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Tarkan_%289%29.jpg?width=500", photos[1].url)
    }

    @Test
    fun commonsImageRel() {
        val lookup = Json.parseToJsonElement(
            """{"relations":[{"type":"image","url":{"resource":"https://commons.wikimedia.org/wiki/File:Sezen%20Aksu%20%C3%A7.jpg"}}]}""",
        )
        assertEquals("Sezen Aksu ç.jpg", MbParse.commonsFileFromRels(lookup))
        assertEquals("", MbParse.wikidataId(lookup))
    }

    @Test
    fun releaseGroups() {
        val albums = MbParse.parseReleaseGroupSearch(fixture("release_group_search.json"), true, 10)
        assertTrue(albums.isNotEmpty())
        for (al in albums) assertTrue(al.primaryType == "Album" || al.primaryType == "EP" || al.primaryType == "Single")
        assertEquals(3, albums[0].images.size)

        val browse = MbParse.sortNewestFirst(MbParse.parseReleaseGroupBrowse(fixture("release_group_browse.json")))
        assertEquals(10, browse.size)
        for (i in 1 until browse.size) assertTrue(browse[i - 1].firstReleaseDate >= browse[i].firstReleaseDate)
        assertTrue(browse.count { MbParse.isOtherRelease(it) } >= 1) // "Metamorfoz Remixes" (Remix)
    }

    @Test
    fun sortNewestFirstPutsUndatedLast() {
        val sorted = MbParse.sortNewestFirst(
            listOf(Album(id = "a"), Album(id = "b", firstReleaseDate = "1999"), Album(id = "c", firstReleaseDate = "2004-05")),
        )
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test
    fun albumRepresentativeReleaseAndTracklist() {
        val rg = fixture("release_group_lookup.json")
        var al = MbParse.parseReleaseGroup(rg)
        assertEquals("Karma", al.name)
        assertEquals("39a72717-1477-43ab-90e7-83d429bf85e1", MbParse.pickRepresentativeRelease(rg)) // TR CD 2001
        al = MbParse.applyRelease(al, fixture("release_lookup.json"))
        assertEquals(3, al.tracks.size)
        assertEquals(3, al.totalTracks)
        assertEquals("39a72717-1477-43ab-90e7-83d429bf85e1", al.releaseId)
        assertTrue(al.label.isNotEmpty())
        assertEquals("9c7b0a23-236a-4fb3-880e-62896df12db4", al.tracks[0].id) // recording id
        assertEquals(262160, al.tracks[0].durationMs)
        assertEquals(1, al.tracks[0].trackNumber)
        assertEquals(al.id, al.tracks[0].album.id)
        assertEquals("Tarkan", al.tracks[0].artistLine)
    }

    @Test
    fun recordings() {
        val tracks = MbParse.rankRecordingSearch(MbParse.parseRecordingHits(fixture("recording_search.json")), "Tarkan", true, 10)
        assertTrue(tracks.isNotEmpty())
        for (t in tracks) assertTrue(t.id.isNotEmpty() && t.name.isNotEmpty())

        val hits = MbParse.parseRecordingHits(fixture("artist_recordings.json"))
        assertEquals(12, hits.size)
        val pop = MbParse.parsePopularity(fixture("lb_popularity_recording.json"))
        assertEquals(2, pop.size)
        assertTrue((pop["9c7b0a23-236a-4fb3-880e-62896df12db4"] ?: 0) > 0)
        val top = MbParse.rankTopTracks(hits, mapOf(hits.last().track.id to 999999L), 5)
        assertEquals(5, top.size)
        assertTrue(top[0].listenCount >= 999999)
        assertTrue(top[0].album.id.isNotEmpty())
    }

    @Test
    fun recordingLookupWrappedAsHit() {
        val rec = Json.parseToJsonElement(
            """{"id":"r1","title":"Song","length":200000,"artist-credit":[{"name":"A","artist":{"id":"a1","name":"A"}}],
               "releases":[{"status":"Bootleg","release-group":{"id":"rg-boot","title":"Boot","primary-type":"Album"}},
                           {"status":"Official","release-group":{"id":"rg-comp","title":"Comp","primary-type":"Album","secondary-types":["Compilation"]}},
                           {"status":"Official","release-group":{"id":"rg-single","title":"Single","primary-type":"Single"}}]}""",
        )
        val hits = MbParse.parseRecordingHits(com.app.shadetube.core.jsonOf("recordings" to JsonArray(listOf(rec))))
        assertEquals(1, hits.size)
        assertTrue(hits[0].official)
        assertEquals(3, hits[0].releaseCount)
        assertEquals("rg-single", hits[0].track.album.id) // clean single beats an album with secondary types
        assertEquals(200000, hits[0].track.durationMs)
    }

    @Test
    fun listenBrainz() {
        val artists = MbParse.parseLbArtists(fixture("lb_sitewide_artists.json"), 10)
        assertEquals(3, artists.size)
        assertTrue(artists[0].listenCount > 0)
        val rgs = MbParse.parseLbReleaseGroups(fixture("lb_sitewide_release_groups.json"), 10)
        assertTrue(rgs.isNotEmpty() && rgs[0].images.isNotEmpty())
        val recs = MbParse.parseLbRecordings(fixture("lb_sitewide_recordings.json"), 10)
        assertEquals(2, recs.size) // same recording listed twice
        assertTrue(recs[0].listenCount > 0 && recs[0].album.images.isNotEmpty())
        val fresh = MbParse.parseFreshReleases(fixture("lb_fresh_releases.json"), 10)
        assertTrue(fresh.isNotEmpty() && fresh[0].id.isNotEmpty())
        val similar = MbParse.parseSimilarArtists(fixture("lb_similar_artists.json"), "4ec2451d-ed0c-4273-b683-4c1312df25fd", 10)
        assertEquals("Mustafa Sandal", similar.firstOrNull()?.name)
    }

    @Test
    fun caaImages() {
        assertTrue(MbParse.caaReleaseImages("", 5).isEmpty())
        assertEquals("https://coverartarchive.org/release/r/7-500.jpg", MbParse.caaReleaseImages("r", 7)[1].url)
        assertEquals("https://coverartarchive.org/release/r/front-250", MbParse.caaReleaseImages("r", 0)[0].url)
    }

    @Test
    fun strictAccessors() {
        val j = Json.parseToJsonElement("""{"s":"x","n":5,"f":2.9,"ns":"42abc","b":true,"nul":null}""")
        assertEquals("x", MbParse.str(j, "s"))
        assertEquals("", MbParse.str(j, "n")) // numbers are not strings
        assertEquals(5L, MbParse.num(j, "n"))
        assertEquals(2L, MbParse.num(j, "f"))
        assertEquals(42L, MbParse.num(j, "ns"))
        assertEquals(7L, MbParse.num(j, "b", 7))
        assertEquals(7L, MbParse.num(j, "nul", 7))
        assertTrue(MbParse.arr(j, "s").isEmpty())
    }

    @Test
    fun garbageNeverCrashes() {
        val junk = listOf<JsonElement?>(
            null, JsonNull, JsonArray(emptyList()), JsonPrimitive(42),
            Json.parseToJsonElement("""{"artists":[null,1,{"id":5}],"releases":"x","media":[{"tracks":[{}]}]}"""),
        )
        for (j in junk) {
            MbParse.parseArtistSearch(j, 5)
            MbParse.parseReleaseGroupSearch(j, true, 5)
            MbParse.parseRecordingHits(j)
            MbParse.pickRepresentativeRelease(j)
            MbParse.applyRelease(Album(), j)
            MbParse.parseLbRecordings(j, 5)
            MbParse.parseFreshReleases(j, 5)
            MbParse.parseSimilarArtists(j, "", 5)
            MbParse.wikidataImageFile(j)
            MbParse.parseLbArtists(j, 5)
            MbParse.parseLbReleaseGroups(j, 5)
            MbParse.parsePopularity(j)
        }
    }
}
