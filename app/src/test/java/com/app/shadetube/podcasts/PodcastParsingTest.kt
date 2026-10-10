package com.app.shadetube.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of tests/podcasts/podcasts_test.cpp: xml, text, dates, feed, directory. */
class PodcastParsingTest {
    private fun throwsPodcastError(f: () -> Unit): Boolean = try {
        f()
        false
    } catch (e: PodcastError) {
        true
    } catch (e: Throwable) {
        false
    }

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.UTF_8))
            is Int -> out.write(p)
            is ByteArray -> out.write(p)
        }
        return out.toByteArray()
    }

    // ---- xml ------------------------------------------------------------------------------------------------------------

    @Test
    fun xmlBasics() {
        val doc = parseXml(
            """<?xml version="1.0" encoding="UTF-8"?>
<!-- a comment -->
<!DOCTYPE rss [ <!ENTITY x "y"> ]>
<root a="1" b='two' c=bare>
  <child>Hello &amp; welcome &#233;&#x1F600; &nbsp;&rsquo;</child>
  <empty/>
  <data><![CDATA[<p>raw & <b>html</b></p>]]></data>
  <?pi ignored?>
  <mixed>a<b/>c</mixed>
</root>""",
        )
        assertEquals(1, doc.children.size)
        val root = doc.children.first()
        assertEquals("root", root.name)
        assertEquals("1", root.attr("a"))
        assertEquals("two", root.attr("b"))
        assertEquals("bare", root.attr("c"))
        assertEquals("", root.attr("missing"))
        assertEquals("Hello & welcome é😀  ’", root.childText("child"))
        assertTrue(root.child("empty") != null && root.child("empty")!!.children.isEmpty())
        assertEquals("<p>raw & <b>html</b></p>", root.childText("data"))
        assertEquals("ac", root.child("mixed")?.text)
        assertNull(root.child("nope"))
    }

    @Test
    fun xmlNamespaces() {
        // whatever the prefix, the usual one; undeclared prefixes stay
        val doc = parseXml(
            """<rss xmlns:it="http://www.itunes.com/DTDs/Podcast-1.0.dtd" xmlns:c="http://purl.org/rss/1.0/modules/content/">
<channel><it:author>A</it:author><c:encoded>E</c:encoded><itunes:x>U</itunes:x><foo:bar>F</foo:bar></channel></rss>""",
        )
        val ch = doc.children.first().child("channel")!!
        assertEquals("A", ch.childText("itunes:author"))
        assertEquals("E", ch.childText("content:encoded"))
        assertEquals("U", ch.childText("itunes:x"))
        assertEquals("F", ch.childText("foo:bar"))
        // the binding ends with its element
        val doc2 = parseXml("""<r><a xmlns:p="http://search.yahoo.com/mrss/"><p:x/></a><p:y/></r>""")
        val r = doc2.children.first()
        assertNotNull(r.child("a")?.child("media:x"))
        assertNotNull(r.child("p:y"))
    }

    @Test
    fun xmlSloppyMarkup() {
        // unclosed elements at the end, stray end tags, a bare '&' and '<' in text
        val doc = parseXml("<a><b>one & two</c> 1 < 2<d>x")
        val a = doc.children.first()
        val b = a.child("b")!!
        assertTrue(b.text.startsWith("one & two"))
        assertEquals("x", b.child("d")?.text)
        assertTrue(b.text.contains("1 < 2"))
    }

    @Test
    fun xmlEncodings() {
        // UTF-8 BOM, UTF-16 LE/BE with BOM, Windows-1252 bytes
        assertEquals("b", parseXml(bytes(0xEF, 0xBB, 0xBF, "<a>b</a>")).children.first().text)
        val text = "<a>çğ</a>"
        val le = bytes(0xFF, 0xFE, text.toByteArray(Charsets.UTF_16LE))
        val be = bytes(0xFE, 0xFF, text.toByteArray(Charsets.UTF_16BE))
        assertEquals("çğ", parseXml(le).children.first().text)
        assertEquals("çğ", parseXml(be).children.first().text)
        val latin = parseXml(bytes("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>caf", 0xE9, " ", 0x93, "q", 0x94, "</a>"))
        assertEquals("café “q”", latin.children.first().text)
        // declared Latin-1 but really UTF-8: UTF-8 wins
        assertEquals("café", parseXml(bytes("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>caf", 0xC3, 0xA9, "</a>")).children.first().text)
    }

    @Test
    fun xmlFailures() {
        assertTrue(throwsPodcastError { parseXml("<a><!-- open") })
        assertTrue(throwsPodcastError { parseXml("<a><![CDATA[ open") })
        assertTrue(throwsPodcastError { parseXml("<a b=\"open></a>") })
        assertTrue(throwsPodcastError { parseXml("<x>".repeat(400)) })
        assertTrue(parseXml("").children.isEmpty())
        assertTrue(parseXml("just text").children.isEmpty())
    }

    @Test
    fun xmlManyBareAmpersandsStayFast() {
        val s = "<a>" + "x & y ".repeat(200_000) + "</a>"
        val t0 = System.nanoTime()
        val doc = parseXml(s)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
        assertTrue(doc.children.first().text.startsWith("x & y"))
    }

    // ---- text -----------------------------------------------------------------------------------------------------------

    @Test
    fun htmlToText() {
        val h = PodcastText::htmlToText
        assertEquals("First line\nSecond bold", h("<p>First&nbsp;line</p><p>Second <b>bold</b></p>"))
        assertEquals("Title\n\nbody", h("<h2>Title</h2><p>body</p>"))
        assertEquals("a\nb\nc", h("a<br>b<br/>c"))
        assertEquals("• one\n• two\n\nafter", h("<ul><li>one</li><li>two</li></ul>after"))
        assertEquals("xyz", h("x<script>var a = '<p>';</script>y<style>p{}</style>z"))
        assertEquals("lots of\n\nspaces", h("  lots   of\n\n\n\n spaces  "))
        assertEquals("Plain\nline breaks\nkept", h("Plain\nline breaks\nkept"))
        assertEquals("Tom & Jerry — \"hi\" &unknown; &", h("Tom &amp; Jerry &#8212; &quot;hi&quot; &unknown; &"))
        assertEquals("link text end", h("<a href=\"x\">link</a> text <!-- hidden --> end"))
        assertEquals("", h(""))
        assertEquals("", h("<p></p><br><br>"))
    }

    // ---- dates ----------------------------------------------------------------------------------------------------------

    @Test
    fun dates() {
        val p = PodcastText::parseRfc822
        val t = 1033563600L // 2002-10-02 13:00:00 UTC
        assertEquals(t, p("Wed, 02 Oct 2002 13:00:00 GMT"))
        assertEquals(t, p("Wed, 02 Oct 2002 15:00:00 +0200"))
        assertEquals(t, p("Wed, 02 Oct 2002 08:00:00 EST"))
        assertEquals(t, p("Wed, 02 Oct 2002 06:00:00 -07:00"))
        assertEquals(t, p("02 Oct 2002 13:00 GMT"))
        assertEquals(t, p("Wed, 2 Oct 02 13:00:00 GMT"))
        assertEquals(t, p("wed, 02 oct 2002 13:00:00 gmt"))
        assertEquals(t, p("Wednesday, 02 October 2002 13:00:00 UTC"))
        assertEquals(t, p("Wed,02 Oct 2002 13:00:00 Z"))
        assertEquals(t, p("Wed, 02 Oct 2002 13:00:00")) // no zone: UTC
        assertEquals(t, p("Wed, 02 Oct 2002 16:00:00 GMT+0300"))
        assertEquals(t, p("  Wed, 02 Oct 2002 13:00:00 +0000  "))
        assertEquals(t, p("Wed, 02 Oct 2002 15:00:00+0200")) // zone glued to the clock
        assertEquals(t, p("2002-10-02T13:00:00Z"))
        assertEquals(t, p("2002-10-02T16:00:00+03:00"))
        assertEquals(t, p("2002-10-02 13:00:00"))
        assertEquals(t, p("2002-10-02T13:00:00.250Z"))
        assertEquals(t - 13 * 3600, p("2002-10-02"))
        assertEquals(1456704000L, p("Mon, 29 Feb 2016 00:00:00 GMT"))
        assertEquals(0L, p(""))
        assertEquals(0L, p("not a date"))
        assertEquals(0L, p("Wed, 45 Oct 2002 13:00:00 GMT"))
        assertEquals(0L, p("Wed, 02 Foo 2002 13:00:00 GMT"))
    }

    @Test
    fun durations() {
        val d = PodcastText::parseDurationMs
        assertEquals(3_600_000L, d("3600"))
        assertEquals(3_600_500L, d("3600.5"))
        assertEquals(3_542_000L, d("59:02"))
        assertEquals(3_723_000L, d("1:02:03"))
        assertEquals(3_600_000L, d(" 01:00:00 "))
        assertEquals(5_400_000L, d("90:00"))
        assertEquals(45_000L, d("45 min")) // a unit after a plain number: seconds as feeds (wrongly) mean
        assertEquals(0L, d(""))
        assertEquals(0L, d("abc"))
        assertEquals(0L, d("-5"))
        assertEquals(0L, d("1:2:3:4"))
        assertEquals(0L, d("1::3"))
        assertEquals(0L, d("0"))
        assertEquals(0L, d("1:30 min")) // a unit is only tolerated on a plain number
        assertEquals(0L, d("999999")) // more than 200 hours
    }

    // ---- feed -----------------------------------------------------------------------------------------------------------

    @Test
    fun feed() {
        val url = "https://feeds.example/show.xml"
        val show = PodcastParsers.parseFeed(FEED, url)
        assertEquals(url, show.feedUrl)
        assertEquals("Test Show", show.title)
        assertEquals("Owner Name", show.author)
        assertEquals("About the show.", show.description)
        assertEquals("https://img.example/show.jpg", show.image)
        assertEquals("https://show.example", show.link)
        assertEquals(listOf("Society & Culture", "History"), show.categories)
        assertEquals(4, show.episodes.size)
        val e3 = show.episodes[0]
        assertEquals("ep-3", e3.guid)
        assertEquals("Newest episode", e3.title)
        assertEquals("https://cdn.example/ep3.m4a?x=1", e3.url)
        assertEquals(0L, e3.length)
        assertEquals(3_725_000L, e3.durationMs)
        assertEquals("https://img.example/ep3.jpg", e3.image)
        assertTrue(e3.explicitContent && e3.season == 2 && e3.number == 14)
        assertEquals("Full notes & links\n\n• one", e3.description)
        assertTrue(e3.notesHtml.startsWith("<p>Full <i>notes</i>")) // the HTML as published
        assertTrue(e3.show == "Test Show" && e3.feedUrl == url)
        assertEquals("ep-2", show.episodes[1].guid)
        assertEquals("https://cdn.example/ep2.mp3", show.episodes[1].url)
        assertEquals("https://img.example/show.jpg", show.episodes[1].image)
        val e1 = show.episodes[2]
        assertTrue(e1.guid == "ep-1" && e1.length == 1234L && e1.durationMs == 750_000L)
        assertEquals("Plain notes\nsecond line", e1.description)
        assertEquals("", e1.notesHtml) // plain text: no HTML copy
        assertEquals(1704103200L, e1.published)
        assertEquals("Undated", show.episodes[3].title)
        assertEquals(0L, show.episodes[3].published)
        assertEquals("https://cdn.example/undated.mp3", show.episodes[3].guid)

        assertTrue(throwsPodcastError { PodcastParsers.parseFeed("<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry/></feed>", "u") })
        assertTrue(throwsPodcastError { PodcastParsers.parseFeed("<html><body>Not found</body></html>", "u") })
        assertTrue(throwsPodcastError { PodcastParsers.parseFeed("<rss></rss>", "u") })
        assertTrue(PodcastParsers.parseFeed("<rss><channel><title>Empty</title></channel></rss>", "u").episodes.isEmpty())
        // The error kinds the UI shows.
        try {
            PodcastParsers.parseFeed("<html><body>Not found</body></html>", "u")
            fail()
        } catch (e: PodcastError) {
            assertEquals(PodcastErrorKind.NotAFeed, PodcastErrorKind.of(e))
        }
        assertEquals(PodcastErrorKind.Gone, PodcastErrorKind.of(PodcastError("HTTP 404")))
        assertEquals(PodcastErrorKind.LocalNetwork, PodcastErrorKind.of(PodcastError("an address on the local network")))
        assertEquals(PodcastErrorKind.Network, PodcastErrorKind.of(PodcastError("timeout")))
    }

    @Test
    fun feedTrickyItems() {
        val xml = """<?xml version="1.0"?>
<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0"
     xmlns:dc="http://purl.org/dc/elements/1.1/">
<channel>
  <itunes:title>Only iTunes title</itunes:title>
  <itunes:author> Some  Author </itunes:author>
  <itunes:summary>&lt;p&gt;Escaped &amp;amp; HTML&lt;/p&gt;</itunes:summary>
  <image><url>https://img.example/rss-image.png</url></image>
  <item>
    <itunes:title>Alt enclosure</itunes:title>
    <guid>alt</guid>
    <dc:date>2024-03-01T08:00:00+01:00</dc:date>
    <podcast:alternateEnclosure type="audio/ogg"><podcast:source uri="https://cdn.example/a.ogg"/></podcast:alternateEnclosure>
    <itunes:image>https://img.example/inline.jpg</itunes:image>
    <itunes:explicit>true</itunes:explicit>
    <itunes:duration>1:00:00</itunes:duration>
  </item>
  <item>
    <guid>ftp-enclosure-but-media</guid>
    <enclosure url="ftp://x/y.mp3"/>
    <media:content xmlns:media="http://search.yahoo.com/mrss/" url="https://cdn.example/m.mp3" medium="audio"/>
    <pubDate>Fri, 01 Mar 2024 06:00:00 GMT</pubDate>
  </item>
</channel>
</rss>"""
        val show = PodcastParsers.parseFeed(xml, "https://f")
        assertEquals("Only iTunes title", show.title)
        assertEquals("Some Author", show.author)
        assertEquals("Escaped & HTML", show.description)
        assertEquals("https://img.example/rss-image.png", show.image)
        assertEquals(2, show.episodes.size)
        val alt = show.episodes[0]
        assertEquals("alt", alt.guid)
        assertEquals("Alt enclosure", alt.title)
        assertEquals("https://cdn.example/a.ogg", alt.url)
        assertEquals("audio/ogg", alt.mimeType)
        assertEquals(1709276400L, alt.published)
        assertEquals("https://img.example/inline.jpg", alt.image)
        assertTrue(alt.explicitContent)
        assertEquals(3_600_000L, alt.durationMs)
        val media = show.episodes[1]
        assertEquals("https://cdn.example/m.mp3", media.url)
        assertEquals("Only iTunes title", media.title) // no title: the show's
        assertEquals("https://img.example/rss-image.png", media.image)
    }

    @Test
    fun atomFeedWithAudio() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <title>Atom Cast</title>
  <author><name>Atom Author</name></author>
  <link rel="alternate" href="https://atom.example/"/>
  <logo>https://atom.example/logo.png</logo>
  <entry>
    <id>tag:atom.example,2024:1</id>
    <title>First</title>
    <published>2024-01-01T10:00:00Z</published>
    <link rel="enclosure" href="https://atom.example/1.mp3" type="audio/mpeg" length="99"/>
    <content type="html">&lt;p&gt;Hi&lt;/p&gt;</content>
  </entry>
  <entry><id>no-audio</id><title>Text post</title><link href="https://atom.example/post"/></entry>
</feed>"""
        val show = PodcastParsers.parseFeed(xml, "https://atom.example/feed")
        assertEquals("Atom Cast", show.title)
        assertEquals("Atom Author", show.author)
        assertEquals("https://atom.example/", show.link)
        assertEquals("https://atom.example/logo.png", show.image)
        assertEquals(1, show.episodes.size)
        val e = show.episodes[0]
        assertEquals("tag:atom.example,2024:1", e.guid)
        assertEquals("https://atom.example/1.mp3", e.url)
        assertEquals("audio/mpeg", e.mimeType)
        assertEquals(99L, e.length)
        assertEquals(1704103200L, e.published)
        assertEquals("Hi", e.description)
        assertEquals("<p>Hi</p>", e.notesHtml)
    }

    @Test
    fun bigFeedIsFastAndBounded() {
        val big = StringBuilder("<rss><channel><title>Big</title>")
        val notes = "x".repeat(400)
        for (i in 0 until 5000) {
            big.append("<item><title>E").append(i).append("</title><guid>").append(i).append("</guid><enclosure url=\"https://x.example/")
                .append(i).append(".mp3\"/><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate><description><![CDATA[<p>")
                .append(notes).append("</p>]]></description></item>")
        }
        big.append("</channel></rss>")
        val bytes = big.toString().toByteArray()
        val t0 = System.nanoTime()
        val show = PodcastParsers.parseFeed(bytes, "u")
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("5000-item feed (${bytes.size / 1024} KB) parsed in $ms ms")
        assertEquals(PodcastParsers.MAX_EPISODES, show.episodes.size)
        assertTrue(ms < 5000)
    }

    // ---- directory ------------------------------------------------------------------------------------------------------

    @Test
    fun directory() {
        val results = PodcastParsers.parseSearch(
            """{"resultCount":4,"results":[
      {"wrapperType":"track","kind":"podcast","collectionId":123,"trackId":123,"artistName":"Maker","collectionName":"Show One",
       "feedUrl":"https://feeds.example/one","artworkUrl100":"https://is1.mzstatic.com/a/100x100bb.jpg","artworkUrl600":"https://is1.mzstatic.com/a/600x600bb.jpg","primaryGenreName":"History"},
      {"wrapperType":"track","kind":"song","trackName":"A song","feedUrl":"https://x"},
      {"wrapperType":"track","kind":"podcast","collectionId":456,"collectionName":"No feed"},
      {"wrapperType":"track","kind":"podcast","collectionId":789,"collectionName":"Two","artistName":"B","feedUrl":"https://feeds.example/two","artworkUrl100":"https://is1.mzstatic.com/b/100x100bb.jpg"}]}""",
        )
        assertEquals(2, results.size)
        assertTrue(results[0].appleId == "123" && results[0].title == "Show One" && results[0].author == "Maker")
        assertTrue(results[0].feedUrl == "https://feeds.example/one" && results[0].genre == "History")
        assertEquals("https://is1.mzstatic.com/a/600x600bb.jpg", results[0].image)
        assertEquals("https://is1.mzstatic.com/b/600x600bb.jpg", results[1].image)
        assertTrue(throwsPodcastError { PodcastParsers.parseSearch("{oops") })
        assertTrue(throwsPodcastError { PodcastParsers.parseSearch("[]") })

        val v2 = PodcastParsers.parseCharts(
            """{"feed":{"title":"Top Shows","results":[
      {"artistName":"NYT","id":"1200361736","name":"The Daily","kind":"podcasts","artworkUrl100":"https://is1.mzstatic.com/c/100x100bb.png",
       "genres":[{"genreId":"1311","name":"News"}],"url":"https://podcasts.apple.com/us/podcast/the-daily/id1200361736"},
      {"id":"","name":"Broken"},{"artistName":"X","id":"42","name":"Second"}]}}""",
        )
        assertEquals(2, v2.size)
        assertTrue(v2[0].appleId == "1200361736" && v2[0].title == "The Daily" && v2[0].author == "NYT" && v2[0].genre == "News")
        assertEquals("https://is1.mzstatic.com/c/600x600bb.png", v2[0].image)
        assertTrue(v2[0].feedUrl.isEmpty())
        val legacy = PodcastParsers.parseCharts(
            """{"feed":{"entry":[{"im:name":{"label":"Legacy Show"},"im:artist":{"label":"L"},
      "im:image":[{"label":"https://is1.mzstatic.com/d/55x55bb.png"},{"label":"https://is1.mzstatic.com/d/170x170bb.png"}],
      "id":{"label":"https://podcasts.apple.com/id99","attributes":{"im:id":"99"}},"category":{"attributes":{"label":"Comedy"}}}]}}""",
        )
        assertTrue(legacy.size == 1 && legacy[0].appleId == "99" && legacy[0].title == "Legacy Show" && legacy[0].genre == "Comedy")
        assertEquals("https://is1.mzstatic.com/d/600x600bb.png", legacy[0].image)
        // A single legacy entry comes as an object, not an array.
        assertEquals(1, PodcastParsers.parseCharts("""{"feed":{"entry":{"im:name":{"label":"Solo"},"id":{"attributes":{"im:id":"7"}}}}}""").size)
        assertTrue(throwsPodcastError { PodcastParsers.parseCharts("""{"feed":{"author":{}}}""") })
    }

    @Test
    fun countryCodes() {
        assertEquals("tr", PodcastClient.countryCode(" TR "))
        assertEquals("us", PodcastClient.countryCode("TUR"))
        assertEquals("us", PodcastClient.countryCode("1A"))
        assertFalse(PodcastText.isHttpUrl("http://"))
        assertTrue(PodcastText.isHttpUrl("HTTPS://a"))
        assertFalse(PodcastText.isHttpUrl("ftp://a"))
    }

    companion object {
        val FEED = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/"
     xmlns:media="http://search.yahoo.com/mrss/">
<channel>
  <title>  Test   Show </title>
  <link>https://show.example</link>
  <description><![CDATA[<p>About the <b>show</b>.</p>]]></description>
  <itunes:owner><itunes:name>Owner Name</itunes:name></itunes:owner>
  <itunes:image href="https://img.example/show.jpg"/>
  <itunes:category text="Society &amp; Culture"><itunes:category text="History"/></itunes:category>
  <item>
    <title>Old episode</title>
    <guid isPermaLink="false">ep-1</guid>
    <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
    <enclosure url="https://cdn.example/ep1.mp3" length="1234" type="audio/mpeg"/>
    <itunes:duration>12:30</itunes:duration>
    <description>Plain notes
second line</description>
  </item>
  <item>
    <title>Newest episode</title>
    <guid>ep-3</guid>
    <pubDate>Wed, 03 Jan 2024 10:00:00 GMT</pubDate>
    <enclosure url="https://cdn.example/ep3.m4a?x=1" length="bogus" type="audio/x-m4a"/>
    <itunes:duration>3725</itunes:duration>
    <itunes:image href="https://img.example/ep3.jpg"/>
    <itunes:explicit>yes</itunes:explicit>
    <itunes:season>2</itunes:season>
    <itunes:episode>14</itunes:episode>
    <description>short</description>
    <content:encoded><![CDATA[<p>Full <i>notes</i> &amp; links</p><ul><li>one</li></ul>]]></content:encoded>
  </item>
  <item>
    <title>Middle via media:content</title>
    <guid>ep-2</guid>
    <pubDate>Tue, 02 Jan 2024 10:00:00 GMT</pubDate>
    <media:content url="https://cdn.example/ep2.mp3" type="audio/mpeg"/>
  </item>
  <item><title>No audio</title><guid>ep-x</guid><pubDate>Thu, 04 Jan 2024 10:00:00 GMT</pubDate></item>
  <item><title>Duplicate</title><guid>ep-1</guid><enclosure url="https://cdn.example/dup.mp3"/></item>
  <item><title>Undated</title><enclosure url="https://cdn.example/undated.mp3"/></item>
  <item><title>Bad URL</title><guid>bad</guid><enclosure url="ftp://cdn.example/x.mp3"/></item>
</channel>
</rss>"""
    }
}
