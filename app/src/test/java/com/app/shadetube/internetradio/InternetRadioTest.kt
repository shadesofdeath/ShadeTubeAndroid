package com.app.shadetube.internetradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Port of tests/radio/radio_test.cpp: parsing (saved radio-browser.info responses), filter, mapping, store. */
class InternetRadioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): String {
        val candidates = listOf(
            File("src/test/java/com/app/shadetube/internetradio/fixtures/$name"),
            File("app/src/test/java/com/app/shadetube/internetradio/fixtures/$name"),
        )
        return candidates.first { it.exists() }.readText()
    }

    private fun station(uuid: String, name: String, codec: String = "MP3", bitrate: Int = 128) = Station(
        uuid = uuid,
        name = name,
        urlResolved = "https://stream.example/$uuid",
        codec = codec,
        bitrate = bitrate,
        countryCode = "TR",
        country = "Turkey",
        tags = listOf("pop", "news"),
        favicon = "https://img.example/$uuid.png",
    )

    private fun throwsRadioError(f: () -> Unit): Boolean = try {
        f()
        false
    } catch (e: RadioError) {
        true
    } catch (e: Throwable) {
        false
    }

    @Test
    fun parsing() {
        val top = RadioJson.parseStations(fixture("topclick.json"))
        assertEquals(12, top.size)
        val s = top.first()
        assertEquals("a3c93ca9-9891-4249-a805-d622dadeed21", s.uuid)
        assertEquals("Cool FM: Lagos", s.name)
        assertTrue(s.codec == "MP3" && s.bitrate == 128 && !s.hls)
        assertTrue(s.countryCode == "NG" && s.country == "Nigeria")
        assertTrue(s.urlResolved.startsWith("https://"))
        assertEquals("https://cdn.onlineradiobox.com/img/l/3/6733.v14.png", s.favicon)
        assertTrue(s.votes == 30 && s.clickCount == 4273)
        for (x in top) assertTrue(x.uuid.isNotEmpty() && x.name.isNotEmpty() && RadioModel.streamUrl(x).isNotEmpty())

        val jazz = RadioJson.parseStations(fixture("search_tag_jazz.json"))
        assertTrue(jazz.isNotEmpty())
        for (x in jazz) assertTrue("jazz" in x.tags)
        val turkey = RadioJson.parseStations(fixture("search_country_tr.json"))
        assertTrue(turkey.isNotEmpty())
        for (x in turkey) assertEquals("TR", x.countryCode)

        val tags = RadioJson.parseTags(fixture("tags.json"))
        assertEquals(40, tags.size)
        assertTrue(tags.first().name == "pop" && tags.first().stationCount > 1000)
        for (i in 1 until tags.size) assertTrue(tags[i - 1].stationCount >= tags[i].stationCount)

        val countries = RadioJson.parseCountries(fixture("countries.json"))
        assertTrue(countries.size > 100)
        fun has(code: String) = countries.any { it.code == code && it.stationCount > 0 }
        assertTrue(has("TR") && has("DE") && has("US"))
        for (c in countries) assertEquals(2, c.code.length)

        val servers = RadioJson.parseServers(fixture("servers.json")) // an IPv4 + IPv6 entry of one host
        assertEquals(listOf("de1.api.radio-browser.info"), servers)
        val hostile = RadioJson.parseServers(
            """[{"name":"evil.example.com"},{"name":"x.api.radio-browser.info"},{"name":"api.radio-browser.info.evil.com"}]""",
        )
        assertEquals(listOf("x.api.radio-browser.info"), hostile)

        // Malformed input.
        assertTrue(throwsRadioError { RadioJson.parseStations("{not json") })
        assertTrue(throwsRadioError { RadioJson.parseStations("""{"stationuuid":"x"}""") })
        assertTrue(throwsRadioError { RadioJson.parseTags("42") })
        val mixed = RadioJson.parseStations(
            """[1, "x", null, {"name":"no uuid","url":"http://a"}, {"stationuuid":"u1","name":"  No   stream "},
            {"stationuuid":"u2","name":"  Good \t Station ","url":"http://s.example/a","codec":"mp3","bitrate":"96",
             "tags":"Pop, ,POP,news","favicon":"javascript:alert(1)","hls":0,"serveruuid":null,"geo_lat":null},
            {"stationuuid":"u2","name":"Duplicate","url":"http://s.example/b"}]""",
        )
        assertEquals(1, mixed.size)
        val m = mixed.first()
        assertEquals("Good Station", m.name)
        assertTrue(m.codec == "MP3" && m.bitrate == 96)
        assertEquals(listOf("pop", "news"), m.tags)
        assertTrue(m.favicon.isEmpty()) // only http(s) images reach the image cache
        assertEquals("http://s.example/a", RadioModel.streamUrl(m))
    }

    @Test
    fun filter() {
        fun with(codec: String, hls: Boolean = false, url: String = "https://s.example/x") =
            station("f", "F", codec).copy(urlResolved = url, hls = hls)
        assertTrue(RadioModel.playable(with("MP3")))
        assertTrue(RadioModel.playable(with("AAC")))
        assertTrue(RadioModel.playable(with("AAC+")))
        assertFalse(RadioModel.playable(with("OGG")))
        assertFalse(RadioModel.playable(with("FLAC")))
        assertFalse(RadioModel.playable(with("AAC,H.264")))
        assertFalse(RadioModel.playable(with("UNKNOWN")))
        assertFalse(RadioModel.playable(with("")))
        assertEquals(RadioModel.PLAY_HLS, RadioModel.playable(with("AAC", true)))
        assertFalse(RadioModel.playable(with("MP3", false, "rtsp://s.example/x")))
        assertFalse(RadioModel.playable(with("MP3", false, "")))
        val fallback = with("MP3", false, "").copy(url = "http://s.example/raw")
        assertTrue(RadioModel.playable(fallback) && RadioModel.streamUrl(fallback) == "http://s.example/raw")

        val kept = RadioModel.filterPlayable(listOf(with("MP3"), with("OGG"), with("AAC+"), with("AAC", true)))
        assertEquals(if (RadioModel.PLAY_HLS) 3 else 2, kept.size)
        // Every station of a saved response passes or fails for its codec only.
        for (s in RadioJson.parseStations(fixture("topclick.json"))) {
            assertEquals((s.codec == "MP3" || s.codec == "AAC" || s.codec == "AAC+") && (!s.hls || RadioModel.PLAY_HLS), RadioModel.playable(s))
        }

        assertEquals("MP3 · 128", RadioModel.codecBadge(with("MP3")))
        assertEquals("AAC+", RadioModel.codecBadge(with("AAC+").copy(bitrate = 0)))
        assertEquals("HLS", RadioModel.codecBadge(with("UNKNOWN", true).copy(bitrate = 0)))
        assertEquals("audio/mpeg", RadioModel.mimeType(with("MP3")))
        assertEquals("audio/aac", RadioModel.mimeType(with("AAC+")))
        assertEquals("application/vnd.apple.mpegurl", RadioModel.mimeType(with("AAC", true)))
        assertEquals("", RadioModel.mimeType(with("OGG")))

        val path = RadioModel.searchPath(RadioQuery(tag = "hip hop", countryCode = "TR", order = RadioQuery.Order.Votes, limit = 20, offset = 40))
        assertTrue(path.contains("tag=hip%20hop&tagExact=true"))
        assertTrue(path.contains("countrycode=TR"))
        assertTrue(path.contains("order=votes&reverse=true&hidebroken=true&limit=20&offset=40"))
        assertTrue(RadioModel.searchPath(RadioQuery(name = "Radyo Ş")).startsWith("/json/stations/search?name=Radyo%20%C5%9E&order=clickcount"))
    }

    @Test
    fun mapping() {
        val labels = DefaultRadioLabels
        val s = station("0d6f7c1a-0000-4000-8000-000000000001", "Radyo Test")
        val t = RadioModel.toTrack(s, labels)
        assertEquals("radio:0d6f7c1a-0000-4000-8000-000000000001", t.id)
        assertEquals("Radyo Test", t.name)
        assertEquals(0, t.durationMs)
        assertTrue(t.album.images.size == 1 && t.album.images[0].url == s.favicon)
        assertTrue(t.album.id.isEmpty()) // never shows up as a "recent album"
        val line = t.artistLine
        assertTrue(line, line.contains(labels.countryName("TR", "Turkey")))
        assertTrue(line.contains("News") && line.contains("Pop"))
        assertTrue(RadioModel.isStationId(t.id))
        assertEquals(s.uuid, RadioModel.uuidOf(t.id))
        assertFalse(RadioModel.isStationId("radio:"))
        assertFalse(RadioModel.isStationId("spotify:track:1"))
        assertTrue(RadioModel.uuidOf("local:abc").isEmpty())
        assertTrue(labels.countryName("TR", "Turkey").isNotEmpty() && labels.countryName("TR", "Turkey") != "TR")
        assertEquals("Atlantis", labels.countryName("", "Atlantis"))
        val bare = station("b", "Bare").copy(countryCode = "", country = "", tags = emptyList())
        assertEquals("Internet radio", RadioModel.subtitle(bare, labels))
        // a known genre comes first
        assertEquals("80s, Moi merino", RadioModel.subtitle(bare.copy(tags = listOf("moi merino", "80s")), labels))
        assertEquals("News", RadioModel.genreLabel("news", labels))
        assertEquals("México", RadioModel.genreLabel("méxico", labels))
        assertEquals("Information", RadioModel.genreLabel("information", labels)) // no Turkish dotted İ for tags
        assertTrue(RadioGenres.isGenre("jazz") && !RadioGenres.isGenre("moi merino"))
        assertEquals("lofi", RadioGenres.key("lo-fi"))
        assertEquals("hiphop", RadioGenres.key("hip hop"))
        assertNull(RadioGenres.key("moi merino"))
        assertTrue(RadioGenres.featured.size >= 12)
        for (g in RadioGenres.featured) assertTrue(g, RadioGenres.isGenre(g)) // all translated
        assertEquals(2, RadioModel.toTracks(listOf(s, bare), labels).size)
        // A UI-supplied label set is used as is.
        val tr = object : RadioLabels {
            override fun countryName(code: String, fallback: String) = if (code == "TR") "Türkiye" else fallback
            override fun knownGenreLabel(tag: String) = mapOf("news" to "Haber", "pop" to "Pop")[tag]
            override val internetRadio = "İnternet radyosu"
        }
        assertEquals("Türkiye · Pop, Haber", RadioModel.subtitle(s, tr))
    }

    @Test
    fun store() {
        val file = File(tmp.root, "radio.json")
        val st = RadioStore()
        st.load(file)
        assertTrue(st.favoritesNow().isEmpty() && st.recentNow().isEmpty() && st.queue.isEmpty() && st.countryNow().isEmpty())
        val rev0 = st.revision.value

        val a = station("a", "Alpha")
        val b = station("b", "Bravo", "AAC+", 64)
        val c = station("c", "Charlie")
        st.setFavorite(a, true)
        st.setFavorite(b, true)
        st.setFavorite(b, true) // no-op
        assertEquals(listOf("b", "a"), st.favoritesNow().map { it.uuid })
        assertTrue(st.isFavorite("a") && !st.isFavorite("c"))
        st.toggleFavorite(a)
        assertTrue(!st.isFavorite("a") && st.favoritesNow().size == 1)
        st.toggleFavorite(a)
        assertEquals("a", st.favoritesNow().first().uuid)
        assertEquals(rev0 + 4, st.revision.value)
        assertEquals(st.favoritesNow(), st.favorites.value)

        st.recordPlayed(a)
        st.recordPlayed(b)
        st.recordPlayed(a)
        st.recordPlayed(a) // already the newest: no change
        assertEquals(listOf("a", "b"), st.recentNow().map { it.uuid })
        for (i in 0 until 40) st.recordPlayed(station("r$i", "R"))
        assertTrue(st.recentNow().size == RadioStore.MAX_RECENT && st.recentNow().first().uuid == "r39")
        st.removeRecent("r39")
        assertEquals("r38", st.recentNow().first().uuid)

        // A long list keeps the window the player's session keeps (100 back, MAX_QUEUE in all).
        val longList = (0 until 700).map { station("L$it", "L") }
        st.rememberQueue(longList, 650)
        assertTrue(st.queue.size == RadioStore.MAX_QUEUE && st.queue.first().uuid == "L200" && st.queue.last().uuid == "L699")
        st.rememberQueue(longList, 30)
        assertTrue(st.queue.size == RadioStore.MAX_QUEUE && st.queue.first().uuid == "L0")
        st.rememberQueue(longList, 400)
        assertTrue(st.queue.first().uuid == "L200" && st.queue.last().uuid == "L699")

        val list = (0 until 250).map { station("q$it", "Q") }
        val before = st.revision.value
        st.rememberQueue(list, 210)
        assertEquals(250, st.queue.size) // fits whole
        assertEquals(before, st.revision.value) // the queue is not shown: no notification
        assertEquals("Q", st.find("q249")?.name) // the registry knows the whole list
        assertNull(st.find("zzz"))
        st.setCountry("de")
        assertEquals("DE", st.countryNow())
        st.remember(c)
        assertEquals("Charlie", st.find("c")?.name)

        assertTrue(st.isDirty)
        assertTrue(st.save())
        assertFalse(st.isDirty)
        assertTrue(file.exists() && !File(file.path + ".tmp").exists())

        val back = RadioStore()
        back.load(file)
        assertEquals("DE", back.countryNow())
        assertEquals(st.favoritesNow().map { RadioJson.station(it) }, back.favoritesNow().map { RadioJson.station(it) })
        assertTrue(back.recentNow().size == st.recentNow().size && back.recentNow().first().uuid == "r38")
        assertTrue(back.queue.size == 250 && back.find("q249") != null)
        assertEquals("https://stream.example/q10", RadioModel.streamUrl(back.find("q10")!!)) // a restored queue plays
        assertTrue(back.find("b")!!.codec == "AAC+" && back.find("b")!!.bitrate == 64)
        assertNull(back.find("c")) // remembered only in memory

        // Fresher data from the server replaces the stored copies.
        val renamed = b.copy(name = "Bravo FM")
        val rev = back.revision.value
        back.refresh(listOf(renamed))
        assertTrue(back.find("b")!!.name == "Bravo FM" && back.revision.value > rev)
        assertTrue(back.favoritesNow().any { it.name == "Bravo FM" })
        val rev2 = back.revision.value
        back.refresh(listOf(renamed)) // same data: no change
        assertEquals(rev2, back.revision.value)

        // Stations the bounded registry dropped are still found in favorites / recent, and their mutators work.
        val alias = RadioStore()
        alias.load(File(tmp.root, "alias.json"))
        alias.setFavorite(station("fx", "Fox"), true)
        alias.setFavorite(station("fy", "Yak"), true)
        alias.recordPlayed(station("r1", "Old"))
        alias.recordPlayed(station("r2", "New"))
        alias.remember((0 until RadioStore.MAX_KNOWN).map { station("k$it", "K") })
        val fav = alias.find("fx")
        assertNotNull(fav)
        alias.toggleFavorite(fav!!)
        assertTrue(!alias.isFavorite("fx") && alias.favoritesNow().size == 1 && alias.find("fx")?.name == "Fox")
        val old = alias.find("r1")
        assertNotNull(old)
        alias.recordPlayed(old!!)
        assertEquals(listOf("r1", "r2"), alias.recentNow().map { it.uuid })
        assertEquals("Old", alias.recentNow()[0].name)

        // A damaged file: kept as .bad, the app starts empty, the next save writes a good file.
        file.writeText("{ \"favorites\": [ {\"uuid\": \"x\", ")
        val broken = RadioStore()
        broken.load(file)
        assertTrue(broken.favoritesNow().isEmpty() && broken.recentNow().isEmpty())
        assertTrue(File(file.path + ".bad").exists())
        broken.setFavorite(a, true)
        assertTrue(broken.save())
        val healed = RadioStore()
        healed.load(file)
        assertTrue(healed.favoritesNow().size == 1 && healed.favoritesNow()[0].uuid == "a")

        // One bad entry never loses the others.
        file.writeText(
            """{"version":1,"favorites":[{"uuid":"ok","name":"Fine","urlResolved":"http://x"},42,{"name":"no uuid"},""" +
                """{"uuid":"ok","name":"dup"}],"recent":"oops","country":"TRX"}""",
        )
        val partial = RadioStore()
        partial.load(file)
        assertTrue(partial.favoritesNow().size == 1 && partial.favoritesNow()[0].name == "Fine")
        assertTrue(partial.recentNow().isEmpty() && partial.countryNow().isEmpty())
    }

    @Test
    fun validBodies() {
        assertTrue(RadioBrowserClient.validBody("[]", true))
        assertTrue(RadioBrowserClient.validBody("﻿ [ {\"a\":1} ]", true))
        assertFalse(RadioBrowserClient.validBody("", true))
        assertFalse(RadioBrowserClient.validBody("<html>maintenance</html>", true))
        assertFalse(RadioBrowserClient.validBody("{\"error\":\"x\"}", true))
        assertTrue(RadioBrowserClient.validBody("{\"ok\":true}", false))
        assertFalse(RadioBrowserClient.validBody("[1,", true))
    }
}
