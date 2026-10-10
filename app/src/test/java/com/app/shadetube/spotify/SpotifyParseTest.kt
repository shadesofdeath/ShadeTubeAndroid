package com.app.shadetube.spotify

import com.app.shadetube.catalog.CardItem
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.core.get
import com.app.shadetube.core.parseJsonOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Ports tests/spotify testHomeFixture + tests/links testSpotifyIds, and covers the other SpotifyApi.cpp parsers. */
class SpotifyParseTest {
    private fun json(s: String) = parseJsonOrNull(s.trimIndent())!!
    private fun fixture(name: String) = parseJsonOrNull(File("src/test/java/com/app/shadetube/spotify/fixtures/$name").readText())!!

    // ---- home --------------------------------------------------------------------------------------------------------

    @Test
    fun homeFixture() {
        val labels = SpotifyLabels(likedSongs = "Beğenilen Şarkılar", playlist = "Çalma listesi", artist = "Sanatçı")
        val shelves = SpotifyParse.parseHome(fixture("home.json")["data"], labels)
        // The untitled "shorts" grid, a template-only title and a podcast-only shelf are dropped.
        assertEquals(3, shelves.size)
        val made = shelves[0]
        assertEquals("Senin için hazırlandı: Test", made.title)
        assertEquals("Her hafta yenilenir", made.label)
        assertEquals(2, made.items.size) // duplicate playlist collapsed
        assertEquals(CardItem.Kind.Playlist, made.items[0].kind)
        assertEquals("spotify:playlist:37i9dQZEVXcDiscoverWkly", made.items[0].id)
        assertEquals("Haftalık Keşif", made.items[0].title)
        assertEquals("Duman, Mor ve Ötesi & daha fazlası. Her pazartesi 'yeni'", made.items[0].subtitle)
        assertEquals(1, made.items[0].images.size)
        assertEquals(0, made.items[0].images[0].width) // null sizes -> 0
        assertEquals("Spotify", made.items[1].subtitle) // empty description -> owner name

        val recent = shelves[1]
        assertEquals("Son çalınanlar", recent.title)
        assertTrue(recent.label.isEmpty())
        assertEquals(4, recent.items.size) // album, artist, Liked Songs, track (episode skipped)
        assertEquals(CardItem.Kind.Album, recent.items[0].kind)
        assertEquals("spotify:album:1El3k8dU3sKyoGUeuyrolH", recent.items[0].id)
        assertEquals("Duman", recent.items[0].subtitle)
        assertEquals(3, recent.items[0].images.size)
        assertEquals(CardItem.Kind.Artist, recent.items[1].kind)
        assertEquals("Mor ve Ötesi", recent.items[1].title)
        assertEquals("Sanatçı", recent.items[1].subtitle)
        assertEquals(2, recent.items[1].images.size)
        assertEquals(CardItem.Kind.Playlist, recent.items[2].kind)
        assertEquals(LIKED_SONGS_URI, recent.items[2].id)
        assertEquals("Beğenilen Şarkılar", recent.items[2].title)
        assertEquals(CardItem.Kind.Track, recent.items[3].kind)
        assertEquals("spotify:album:1El3k8dU3sKyoGUeuyrolH", recent.items[3].track!!.album.id)
        assertEquals("Duman", recent.items[3].subtitle)

        val mixes = shelves[2]
        assertEquals("En çok dinlediğin mixler", mixes.title)
        assertTrue(mixes.label.isEmpty()) // a long subtitle does not fit the mono label
        assertEquals(2, mixes.items.size)
        assertEquals("Daily Mix 1", mixes.items[0].title)
        assertEquals("Duman, Teoman ve daha fazlası <3", mixes.items[0].subtitle) // unmatched '<' kept, not truncated
        assertEquals(CardItem.Kind.Artist, mixes.items[1].kind) // typed only by its ResponseWrapper

        // Garbage in -> nothing out, no throw.
        assertTrue(SpotifyParse.parseHome(null).isEmpty())
        assertTrue(SpotifyParse.parseHome(json("""{"home":{"sectionContainer":{"sections":{"items":[1,"x",{}]}}}}""")).isEmpty())
    }

    @Test
    fun plainTextAndLabels() {
        assertEquals("a & b", SpotifyParse.plainText("<b>a</b>\n &amp;   b "))
        assertEquals("é 😀 &#0; &bogus; x", SpotifyParse.plainText("&#xE9; &#128512; &#0; &bogus; x"))
        assertEquals("Duman <3", SpotifyParse.plainText("Duman <3"))
        assertEquals("x", SpotifyParse.labelText(json(""""x"""")))
        assertEquals("", SpotifyParse.labelText(json("""{"translatedBaseText":"Made For {0}"}""")))
        assertEquals("Good evening", SpotifyParse.labelText(json("""{"translatedBaseText":"Good evening"}""")))
        assertTrue(SpotifyParse.isLikedSongsUri("spotify:user:@:collection"))
        assertTrue(SpotifyParse.isLikedSongsUri("spotify:user:berkay:collection"))
        assertFalse(SpotifyParse.isLikedSongsUri("spotify:user::collection"))
    }

    // ---- ids / metadata -------------------------------------------------------------------------------------------------

    @Test
    fun base62Gids() {
        // librespot's conversion vector
        assertEquals("b39fe8081e1f4c54be38e8d6f9f12bb9", SpotifyParse.gidFromId("5sWHDYs0csV6RS48xBl0tH"))
        assertEquals("5sWHDYs0csV6RS48xBl0tH", SpotifyParse.idFromGid("b39fe8081e1f4c54be38e8d6f9f12bb9"))
        assertEquals("5sWHDYs0csV6RS48xBl0tH", SpotifyParse.idFromGid("B39FE8081E1F4C54BE38E8D6F9F12BB9"))
        assertEquals("00000000000000000000000000000000", SpotifyParse.gidFromId("0000000000000000000000"))
        assertEquals("0000000000000000000000", SpotifyParse.idFromGid("00000000000000000000000000000000"))
        assertEquals("7N42dgm5tFLK9N8MT7fHC7", SpotifyParse.idFromGid("ffffffffffffffffffffffffffffffff"))
        assertEquals("ffffffffffffffffffffffffffffffff", SpotifyParse.gidFromId("7N42dgm5tFLK9N8MT7fHC7"))
        assertEquals("", SpotifyParse.gidFromId("7N42dgm5tFLK9N8MT7fHC8")) // 2^128: one past the largest id
        assertEquals("", SpotifyParse.gidFromId("zzzzzzzzzzzzzzzzzzzzzz"))
        assertEquals("", SpotifyParse.gidFromId("short"))
        assertEquals("", SpotifyParse.gidFromId("5sWHDYs0csV6RS48xBl0t!"))
        assertEquals("", SpotifyParse.idFromGid("b39fe8081e1f4c54be38e8d6f9f12bb"))
        assertEquals("", SpotifyParse.idFromGid("x39fe8081e1f4c54be38e8d6f9f12bb9"))
        // Round trips.
        for (id in listOf("4uLU6hMCjMI75M1A2tKUQC", "1rfofaqEpACxVEHIZBJe6W", "37i9dQZF1DXcBWIGoYBM5M"))
            assertEquals(id, SpotifyParse.idFromGid(SpotifyParse.gidFromId(id)))
    }

    @Test
    fun trackMetadata() {
        val t = SpotifyParse.parseTrackMetadata(
            json(
                """{
                "gid": "b39fe8081e1f4c54be38e8d6f9f12bb9", "name": "Synthetic Song", "number": 3, "duration": 201234,
                "explicit": true,
                "artist": [{"gid": "00000000000000000000000000000001", "name": "First Artist"},
                           {"gid": "00000000000000000000000000000002", "name": "Second Artist"}],
                "album": {"gid": "ffffffffffffffffffffffffffffffff", "name": "Synthetic Album",
                          "artist": [{"gid": "00000000000000000000000000000001", "name": "First Artist"}],
                          "cover_group": {"image": [
                              {"file_id": "AB67616D0000B273000000000000000000000001", "size": "DEFAULT", "width": 300, "height": 300},
                              {"file_id": "ab67616d00004851000000000000000000000001", "size": "SMALL", "width": 64, "height": 64}]}}
            }""",
            ),
        )
        assertEquals("spotify:track:5sWHDYs0csV6RS48xBl0tH", t.id)
        assertEquals("Synthetic Song", t.name)
        assertEquals(201234, t.durationMs)
        assertEquals(3, t.trackNumber)
        assertTrue(t.explicitContent)
        assertEquals(2, t.artists.size)
        assertEquals("spotify:artist:0000000000000000000001", t.artists[0].id)
        assertEquals("Second Artist", t.artists[1].name)
        assertEquals("spotify:album:7N42dgm5tFLK9N8MT7fHC7", t.album.id)
        assertEquals("Synthetic Album", t.album.name)
        assertEquals(2, t.album.images.size)
        assertEquals("https://i.scdn.co/image/ab67616d0000b273000000000000000000000001", t.album.images[0].url)
        assertEquals(300, t.album.images[0].width)
        // camelCase variant, album artists as the fallback credit, no gid = nothing.
        val t2 = SpotifyParse.parseTrackMetadata(
            json(
                """{
                "gid": "b39fe8081e1f4c54be38e8d6f9f12bb9", "name": "Other",
                "album": {"gid": "b39fe8081e1f4c54be38e8d6f9f12bb9", "name": "A",
                          "artist": [{"gid": "00000000000000000000000000000003", "name": "Album Artist"}],
                          "coverGroup": {"image": [{"fileId": "ab67616d0000b273000000000000000000000002", "width": 640}]}}
            }""",
            ),
        )
        assertEquals(1, t2.artists.size)
        assertEquals("Album Artist", t2.artists[0].name)
        assertEquals(1, t2.album.images.size)
        assertEquals(640, t2.album.images[0].width)
        assertTrue(SpotifyParse.parseTrackMetadata(json("""{"name": "x"}""")).id.isEmpty())
        assertTrue(SpotifyParse.parseTrackMetadata(null).id.isEmpty())
    }

    // ---- library / playlists ---------------------------------------------------------------------------------------------

    @Test
    fun libraryPlaylistsPage() {
        val data = json(
            """{"me":{"libraryV3":{"totalCount":4,"items":[
              {"item":{"__typename":"PseudoPlaylistResponseWrapper","data":{"__typename":"PseudoPlaylist","uri":"spotify:collection:tracks",
                "name":"Liked Songs","count":42,"image":{"sources":[{"url":"https://misc.scdn.co/liked.png","width":300,"height":300}]}}}},
              {"item":{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist","uri":"spotify:playlist:p1","name":"Mine",
                "description":"d","images":{"items":[{"sources":[{"url":"https://i/1","width":640,"height":640}]}]},
                "ownerV2":{"data":{"username":"me","name":"Me Myself"}}}}},
              {"item":{"__typename":"FolderResponseWrapper","data":{"__typename":"Folder","uri":"spotify:user:me:folder:f1","name":"F"}}},
              {"item":{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist","uri":"spotify:playlist:p2","name":"Theirs",
                "ownerV2":{"data":{"uri":"spotify:user:other","name":"Other"}},"currentUserCapabilities":{"canEditItems":true}}}},
              {"item":{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist","uri":"spotify:playlist:ep","name":"Your Episodes",
                "format":"listen-later","ownerV2":{"data":{"username":"me"}}}}}
            ]}}}""",
        )
        val page = SpotifyParse.parseLibraryPlaylistsPage(data, "me", SpotifyLabels(likedSongs = "Beğenilen Şarkılar"))!!
        assertEquals(5, page.rawCount)
        assertEquals(4, page.totalCount)
        assertEquals(listOf(LIKED_SONGS_URI, "spotify:playlist:p1", "spotify:playlist:p2", "spotify:playlist:ep"), page.playlists.map { it.id })
        val liked = page.playlists[0]
        assertEquals("Beğenilen Şarkılar", liked.name)
        assertEquals(42, liked.totalTracks)
        assertTrue(liked.countKnown)
        assertEquals("me", liked.ownerId)
        assertFalse(liked.editable)
        val mine = page.playlists[1]
        assertTrue(mine.owned && mine.editable)
        assertFalse(mine.countKnown) // filled from the rootlist
        assertEquals("Me Myself", mine.ownerName)
        assertEquals(640, mine.images[0].width)
        val theirs = page.playlists[2]
        assertEquals("other", theirs.ownerId)
        assertFalse(theirs.owned)
        assertTrue(theirs.editable) // collaborative
        val episodes = page.playlists[3]
        assertFalse(episodes.owned || episodes.editable)
        assertNull(SpotifyParse.parseLibraryPlaylistsPage(json("""{"me":{"libraryV3":{"items":[]}}}"""), "me"))
    }

    @Test
    fun likedSongsPageCountsSkippedRows() {
        val data = json(
            """{"me":{"library":{"tracks":{"totalCount":5,"items":[
              {"addedAt":{"isoString":"2022-06-14T16:13:04.660Z"},"track":{"_uri":"spotify:track:a","data":{"name":"A",
                "duration":{"totalMilliseconds":1000},"artists":{"items":[{"uri":"spotify:artist:x","profile":{"name":"X"}}]},
                "albumOfTrack":{"uri":"spotify:album:al","name":"Al","coverArt":{"sources":[{"url":"https://i/a"}]}},
                "contentRating":{"label":"EXPLICIT"},"playability":{"playable":false}}}},
              {"track":{"_uri":"spotify:episode:e","data":null}},
              {"addedAt":{"isoString":"1970-01-01T00:00:00Z"},"track":{"data":{"uri":"spotify:track:b","name":"B"}}}
            ]}}}}""",
        )
        val r = SpotifyParse.parseSavedTracks(data, 10, SpotifyLabels(likedSongs = "Liked"))
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), r.page.items.map { it.id })
        assertEquals(13, r.page.next) // the skipped episode row still takes an offset
        assertFalse(r.page.hasMore) // 13 >= 5
        val a = r.page.items[0]
        assertEquals(LocalDateTime.of(2022, 6, 14, 16, 13, 4).toEpochSecond(ZoneOffset.UTC), a.addedAt)
        assertEquals(1000, a.durationMs)
        assertTrue(a.explicitContent)
        assertFalse(a.playable)
        assertEquals("X", a.artistLine)
        assertEquals("spotify:album:al", a.album.id)
        assertEquals(0L, r.page.items[1].addedAt) // epoch placeholder
        assertEquals(LIKED_SONGS_URI, r.meta!!.id)
        assertEquals("Liked", r.meta!!.name)
        assertEquals(5, r.meta!!.totalTracks)
    }

    @Test
    fun playlistPageWithUidsAndHeader() {
        val data = json(
            """{"playlistV2":{"__typename":"Playlist","uri":"spotify:playlist:p1","name":"Road",
              "description":"Best of <a href=\"spotify:artist:1\">Duman</a> &amp; more","format":"",
              "images":{"items":[{"sources":[{"url":"https://i/p","width":300,"height":300}]}]},
              "ownerV2":{"data":{"username":"me","name":"Me"}},"currentUserCapabilities":{"canEditItems":true},
              "content":{"totalCount":5,"items":[
                {"uid":"u1","addedAt":{"isoString":"2023-01-02T03:04:05Z"},"itemV2":{"data":{"__typename":"Track","uri":"spotify:track:t1","name":"T1",
                  "trackDuration":{"totalMilliseconds":2000},"playcount":"12345"}}},
                {"uid":"u2","itemV2":{"data":{"__typename":"Episode","uri":"spotify:episode:e1","name":"E1"}}},
                {"uid":"u3","itemV2":{"data":{"__typename":"Track","uri":"spotify:track:t2","name":"T2"}}}
              ]}}}""",
        )
        val r = SpotifyParse.parsePlaylistTracks(data, "spotify:playlist:p1", 0, "me")
        assertEquals(listOf("u1", "u3"), r.page.items.map { it.uid })
        assertEquals(3, r.page.next)
        assertTrue(r.page.hasMore)
        assertEquals(5, r.page.total)
        assertEquals(2000, r.page.items[0].durationMs)
        assertEquals(12345L, r.page.items[0].listenCount)
        val meta = r.meta!!
        assertEquals("Road", meta.name)
        assertEquals("Best of Duman & more", meta.description)
        assertTrue(meta.owned && meta.editable && meta.countKnown)
        assertEquals(5, meta.totalTracks)
        assertEquals("Me", r.ownerName)
        // No content: the header without a count.
        val bare = SpotifyParse.parsePlaylistTracks(json("""{"playlistV2":{"name":"X"}}"""), "spotify:playlist:x", 0, "")
        assertEquals("spotify:playlist:x", bare.meta!!.id)
        assertFalse(bare.meta!!.countKnown)
        assertTrue(bare.page.items.isEmpty())
    }

    @Test
    fun albumSearchArtist() {
        val album = SpotifyParse.parseAlbum(
            json(
                """{"albumUnion":{"uri":"spotify:album:a","name":"Album","type":"ALBUM","label":"Lbl",
                  "date":{"isoString":"1997-06-14T00:00:00Z"},"coverArt":{"sources":[{"url":"https://i/c","width":640,"height":640}]},
                  "artists":{"items":[{"uri":"spotify:artist:x","profile":{"name":"X"}}]},
                  "tracksV2":{"totalCount":12,"items":[{"track":{"uri":"spotify:track:1","name":"One","trackNumber":1}},{"nope":1}]}}}""",
            ),
        )!!
        assertEquals("1997-06-14", album.firstReleaseDate)
        assertEquals(12, album.totalTracks)
        assertEquals(1, album.tracks.size)
        assertEquals("X", album.tracks[0].artistLine) // album artists as the fallback credit
        assertEquals("spotify:album:a", album.tracks[0].album.id)
        assertEquals("Lbl", album.label)
        assertNull(SpotifyParse.parseAlbum(json("""{"albumUnion":null}""")))
        assertEquals("2001", SpotifyParse.dateFrom(json("""{"year":2001}""")))

        val sr = SpotifyParse.parseSearch(
            json(
                """{"searchV2":{"artists":{"items":[{"data":{"uri":"spotify:artist:x","profile":{"name":"X"},
                  "visuals":{"avatarImage":{"sources":[{"url":"https://i/x"}]}}}}]},
                  "albumsV2":{"items":[{"data":{"uri":"spotify:album:a","name":"A"}}]},
                  "tracksV2":{"items":[{"item":{"data":{"uri":"spotify:track:t","name":"T"}}},{"item":{}}]}}}""",
            ),
        )
        assertEquals(1, sr.artists.size)
        assertEquals(1, sr.artists[0].images.size)
        assertEquals("A", sr.albums[0].name)
        assertEquals(listOf("spotify:track:t"), sr.tracks.map { it.id })

        val ap = SpotifyParse.parseArtistPage(
            json(
                """{"artistUnion":{"uri":"spotify:artist:x","profile":{"name":"X"},"stats":{"monthlyListeners":12345},
                  "discography":{"topTracks":{"items":[{"track":{"uri":"spotify:track:1","name":"One"}},{"track":{"name":"no uri"}}]},
                    "popularReleasesAlbums":{"items":[{"uri":"spotify:album:a","name":"A"},{"name":"no uri"}]}},
                  "relatedContent":{"relatedArtists":{"items":[{"uri":"spotify:artist:y","profile":{"name":"Y"}}]}}}}""",
            ),
        )
        assertEquals("X", ap.artist.name)
        assertEquals(12345L, ap.artist.listenCount)
        assertEquals(1, ap.topTracks.size)
        assertEquals(1, ap.albums.size)
        assertEquals("Y", ap.similar[0].name)
    }

    @Test
    fun savedLookupRadioProfile() {
        val saved = SpotifyParse.parseTracksSaved(
            json("""{"lookup":[{"data":{"saved":true}},{"saved":true},{"data":{"saved":false}}]}"""),
            4,
        )
        assertEquals(listOf(true, true, false, false), saved)
        assertEquals(
            "spotify:playlist:37i9dQZF1E8",
            SpotifyParse.parseRadio(json("""{"mediaItems":[{"uri":"spotify:track:x"},{"uri":"spotify:playlist:37i9dQZF1E8"}]}""")),
        )
        assertEquals("", SpotifyParse.parseRadio(json("""{"mediaItems":[]}""")))
        val me = SpotifyParse.parseProfile(
            json(
                """{"me":{"profile":{"uri":"spotify:user:berkay","name":"Berkay","username":"berkay",
                  "avatar":{"sources":[{"url":"https://i/small"},{"url":"https://i/large"}]}}}}""",
            ),
        )
        assertTrue(me.valid)
        assertEquals("https://i/large", me.imageUrl) // largest last
        assertEquals("berkay", me.username)
    }

    @Test
    fun isoDates() {
        assertEquals(0L, SpotifyParse.isoToUnix("garbage"))
        assertEquals(0L, SpotifyParse.isoToUnix("1970-01-01T00:00:00.000Z"))
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59).toEpochSecond(ZoneOffset.UTC), SpotifyParse.isoToUnix("2024-02-29T23:59:59Z"))
    }

    // ---- request shapes ---------------------------------------------------------------------------------------------------

    @Test
    fun requestShapes() {
        assertEquals("tr-TR,tr;q=0.9,en;q=0.5", SpotifyApi.acceptLanguage("tr"))
        assertEquals("de-DE,de;q=0.9,en;q=0.5", SpotifyApi.acceptLanguage("", Locale("de", "AT")))
        assertEquals("nl-NL,nl;q=0.9,en;q=0.5", SpotifyApi.acceptLanguage("", Locale("nl", "NL")))
        assertEquals("spotify:track:abc", SpotifyApi.toUri("track", "abc"))
        assertEquals("spotify:album:abc", SpotifyApi.toUri("track", "spotify:album:abc"))
        val vars = SpotifyApi.libraryVariables("Playlists", 50, flatten = true).toString()
        assertTrue(vars.contains("\"filters\":[\"Playlists\"]"))
        assertTrue(vars.contains("\"order\":null"))
        assertTrue(vars.contains("\"includeFoldersWhenFlattening\":true"))
        assertEquals(
            """{"kind":"UPDATE_LIST_ATTRIBUTES","updateListAttributes":{"newAttributes":{"values":{"name":"N"}}}}""",
            SpotifyApi.nameOp("N", "").toString(),
        )
        assertEquals(
            """{"deltas":[{"ops":[],"info":{"source":{"client":"WEBPLAYER"}}}]}""",
            SpotifyApi.listChanges(kotlinx.serialization.json.JsonArray(emptyList())).toString(),
        )
        assertNotNull(SpotifyApi.localTimeZone())
    }

    @Test
    fun loginHelpers() {
        assertEquals("abc=", SpotifyLoginActivity.findCookie("sp_t=1; sp_dc=abc=; sp_key=2", "sp_dc"))
        assertNull(SpotifyLoginActivity.findCookie("sp_t=1", "sp_dc"))
        assertNull(SpotifyLoginActivity.findCookie(null, "sp_dc"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
            SpotifyLoginActivity.loginUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36",
            ),
        )
    }
}
