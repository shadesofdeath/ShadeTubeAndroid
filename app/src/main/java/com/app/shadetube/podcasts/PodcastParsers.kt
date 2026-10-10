package com.app.shadetube.podcasts

import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isArray
import com.app.shadetube.core.isObject
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.serialization.json.JsonElement

/**
 * Feed and directory parsers (port of app/Podcasts.cpp parseFeed / parseSearch / parseCharts). Pure: no network.
 */
object PodcastParsers {
    /** At most this many episodes per show (newest first). */
    const val MAX_EPISODES = 3000

    /** Show notes are capped at this many UTF-8 bytes (text and HTML each). */
    const val MAX_DESCRIPTION = 16 * 1024

    // ---- feeds -----------------------------------------------------------------------------------------------------------

    /**
     * RSS 2.0 with the iTunes namespace (and content:encoded / media:content / Podcasting 2.0 fallbacks). Items without
     * an http(s) media URL are skipped, repeated guids too; at most [MAX_EPISODES], newest first. Not RSS ->
     * [PodcastError].
     *
     * Android addition: an Atom feed whose entries carry audio (`<link rel="enclosure">`) is read too; an Atom feed
     * without audio is refused as before ("an Atom feed, not a podcast (RSS) feed").
     */
    fun parseFeed(xml: ByteArray, feedUrl: String): Show = parseFeedDoc(parseXml(xml), feedUrl)

    fun parseFeed(xml: String, feedUrl: String): Show = parseFeedDoc(parseXml(xml), feedUrl)

    private fun parseFeedDoc(doc: XmlNode, feedUrl: String): Show {
        val rss = doc.child("rss")
        if (rss == null) {
            val atom = doc.children.firstOrNull { it.name == "feed" || it.name == "atom:feed" }
            if (atom != null) {
                val show = parseAtom(atom, feedUrl)
                if (show.episodes.isEmpty()) throw PodcastError("an Atom feed, not a podcast (RSS) feed")
                return show
            }
            throw PodcastError("not an RSS feed")
        }
        val channel = rss.child("channel") ?: throw PodcastError("RSS without a channel")
        var title = oneLine(channel.childText("title"))
        if (title.isEmpty()) title = oneLine(channel.childText("itunes:title"))
        var author = ""
        for (key in arrayOf("itunes:author", "googleplay:author", "dc:creator", "managingEditor", "author")) {
            if (author.isNotEmpty()) break
            author = oneLine(channel.childText(key))
        }
        if (author.isEmpty()) channel.child("itunes:owner")?.let { author = oneLine(it.childText("itunes:name")) }
        var desc = channel.childText("description")
        if (desc.isEmpty()) desc = channel.childText("itunes:summary")
        if (desc.isEmpty()) desc = channel.childText("googleplay:description")
        val link = channel.childText("link").takeIf { PodcastText.isHttpUrl(it) } ?: ""
        val categories = ArrayList<String>()
        collectCategories(channel, categories)
        val showImage = imageOf(channel)

        val seen = HashSet<String>()
        val episodes = ArrayList<Episode>()
        for (item in channel.children) {
            if (item.name != "item") continue
            var url = ""
            var mime = ""
            var length = 0L
            item.child("enclosure")?.let { enc ->
                url = trimXml(enc.attr("url"))
                mime = asciiLower(trimXml(enc.attr("type")))
                length = maxOf(0L, atoi64(trimXml(enc.attr("length"))))
            }
            if (!PodcastText.isHttpUrl(url)) { // media:content / podcast:alternateEnclosure as fallbacks
                url = ""
                for (c in item.children) {
                    if (c.name == "media:content") {
                        val type = asciiLower(trimXml(c.attr("type")))
                        val u = trimXml(c.attr("url"))
                        if (PodcastText.isHttpUrl(u) && (type.startsWith("audio/") || c.attr("medium") == "audio")) {
                            url = u
                            mime = type
                            break
                        }
                    }
                    if (c.name == "podcast:alternateEnclosure") {
                        val src = c.child("podcast:source")
                        if (src != null && PodcastText.isHttpUrl(trimXml(src.attr("uri")))) {
                            url = trimXml(src.attr("uri"))
                            mime = asciiLower(trimXml(c.attr("type")))
                            break
                        }
                    }
                }
            }
            if (!PodcastText.isHttpUrl(url)) continue
            val guid = item.childText("guid").ifEmpty { url }
            if (!seen.add(guid)) continue
            var epTitle = oneLine(item.childText("title"))
            if (epTitle.isEmpty()) epTitle = oneLine(item.childText("itunes:title"))
            if (epTitle.isEmpty()) epTitle = title
            var notes = item.childText("content:encoded")
            if (notes.isEmpty()) notes = item.childText("description")
            if (notes.isEmpty()) notes = item.childText("itunes:summary")
            var published = PodcastText.parseRfc822(item.childText("pubDate"))
            if (published == 0L) published = PodcastText.parseRfc822(item.childText("dc:date"))
            episodes.add(
                Episode(
                    guid = guid,
                    feedUrl = feedUrl,
                    title = epTitle,
                    show = title,
                    author = author,
                    description = capUtf8(PodcastText.htmlToText(notes), MAX_DESCRIPTION),
                    notesHtml = notesHtml(notes),
                    image = imageOf(item).ifEmpty { showImage },
                    url = url,
                    mimeType = mime,
                    length = length,
                    durationMs = PodcastText.parseDurationMs(item.childText("itunes:duration")),
                    published = published,
                    season = maxOf(0, atoi(item.childText("itunes:season"))),
                    number = maxOf(0, atoi(item.childText("itunes:episode"))),
                    explicitContent = truthy(item.childText("itunes:explicit")),
                ),
            )
            if (episodes.size >= MAX_EPISODES) break
        }
        return Show(
            feedUrl = feedUrl,
            title = title,
            author = author,
            description = capUtf8(PodcastText.htmlToText(desc), MAX_DESCRIPTION),
            image = showImage,
            link = link,
            categories = categories,
            episodes = newestFirst(episodes),
        )
    }

    /** Newest first (feeds list them either way); undated ones keep their order after the dated. */
    private fun newestFirst(list: List<Episode>): List<Episode> = list.sortedWith { a, b ->
        if ((a.published == 0L) != (b.published == 0L)) {
            if (a.published != 0L) -1 else 1
        } else {
            b.published.compareTo(a.published)
        }
    }

    private fun notesHtml(notes: String): String =
        if (PodcastText.hasMarkup(notes)) capUtf8(notes, MAX_DESCRIPTION) else ""

    private fun imageOf(n: XmlNode): String {
        n.child("itunes:image")?.let { img ->
            var href = trimXml(img.attr("href"))
            if (href.isEmpty()) href = trimXml(img.text) // <itunes:image>url</itunes:image> (seen in the wild)
            if (PodcastText.isHttpUrl(href)) return href
        }
        n.child("image")?.let { img ->
            val url = img.childText("url")
            if (PodcastText.isHttpUrl(url)) return url
            val href = trimXml(img.attr("href"))
            if (PodcastText.isHttpUrl(href)) return href
        }
        for (c in n.children) {
            if (c.name == "media:thumbnail" || (c.name == "media:content" && c.attr("medium") == "image")) {
                val url = trimXml(c.attr("url"))
                if (PodcastText.isHttpUrl(url)) return url
            }
        }
        return ""
    }

    private fun truthy(v: String): Boolean {
        val l = asciiLower(trimXml(v))
        return l == "yes" || l == "true" || l == "explicit"
    }

    private fun collectCategories(n: XmlNode, out: MutableList<String>) {
        for (c in n.children) {
            if (c.name != "itunes:category") continue
            val t = oneLine(c.attr("text"))
            if (t.isNotEmpty() && t !in out && out.size < 8) out.add(t)
            collectCategories(c, out)
        }
    }

    // ---- Atom (Android addition) -------------------------------------------------------------------------------------------

    private fun XmlNode.atomChild(n: String): XmlNode? = child(n) ?: child("atom:$n")
    private fun XmlNode.atomText(n: String): String = atomChild(n)?.let { trimXml(it.textBuilder) } ?: ""
    private fun XmlNode.atomChildren(n: String): List<XmlNode> = children.filter { it.name == n || it.name == "atom:$n" }

    private fun parseAtom(feed: XmlNode, feedUrl: String): Show {
        val title = oneLine(feed.atomText("title")).ifEmpty { oneLine(feed.childText("itunes:title")) }
        var author = oneLine(feed.childText("itunes:author"))
        if (author.isEmpty()) author = oneLine(feed.atomChild("author")?.atomText("name") ?: "")
        var desc = feed.atomText("subtitle")
        if (desc.isEmpty()) desc = feed.childText("itunes:summary")
        var image = imageOf(feed)
        if (image.isEmpty()) image = feed.atomText("logo").takeIf { PodcastText.isHttpUrl(it) } ?: ""
        if (image.isEmpty()) image = feed.atomText("icon").takeIf { PodcastText.isHttpUrl(it) } ?: ""
        val link = feed.atomChildren("link")
            .firstOrNull { (it.attr("rel").isEmpty() || it.attr("rel") == "alternate") && PodcastText.isHttpUrl(trimXml(it.attr("href"))) }
            ?.let { trimXml(it.attr("href")) } ?: ""
        val categories = ArrayList<String>()
        collectCategories(feed, categories)
        val seen = HashSet<String>()
        val episodes = ArrayList<Episode>()
        for (entry in feed.atomChildren("entry")) {
            val enc = entry.atomChildren("link").firstOrNull {
                it.attr("rel") == "enclosure" && PodcastText.isHttpUrl(trimXml(it.attr("href")))
            } ?: continue
            val url = trimXml(enc.attr("href"))
            val guid = entry.atomText("id").ifEmpty { url }
            if (!seen.add(guid)) continue
            var notes = entry.atomText("content")
            if (notes.isEmpty()) notes = entry.atomText("summary")
            if (notes.isEmpty()) notes = entry.childText("itunes:summary")
            var published = PodcastText.parseRfc822(entry.atomText("published"))
            if (published == 0L) published = PodcastText.parseRfc822(entry.atomText("updated"))
            episodes.add(
                Episode(
                    guid = guid,
                    feedUrl = feedUrl,
                    title = oneLine(entry.atomText("title")).ifEmpty { title },
                    show = title,
                    author = author,
                    description = capUtf8(PodcastText.htmlToText(notes), MAX_DESCRIPTION),
                    notesHtml = notesHtml(notes),
                    image = imageOf(entry).ifEmpty { image },
                    url = url,
                    mimeType = asciiLower(trimXml(enc.attr("type"))),
                    length = maxOf(0L, atoi64(trimXml(enc.attr("length")))),
                    durationMs = PodcastText.parseDurationMs(entry.childText("itunes:duration")),
                    published = published,
                    season = maxOf(0, atoi(entry.childText("itunes:season"))),
                    number = maxOf(0, atoi(entry.childText("itunes:episode"))),
                    explicitContent = truthy(entry.childText("itunes:explicit")),
                ),
            )
            if (episodes.size >= MAX_EPISODES) break
        }
        return Show(
            feedUrl = feedUrl,
            title = title,
            author = author,
            description = capUtf8(PodcastText.htmlToText(desc), MAX_DESCRIPTION),
            image = image,
            link = link,
            categories = categories,
            episodes = newestFirst(episodes),
        )
    }

    // ---- directory JSON ---------------------------------------------------------------------------------------------------

    private fun parseJsonBody(s: String): JsonElement =
        parseJsonOrNull(s) ?: throw PodcastError("podcast directory: bad JSON")

    /** mzstatic artwork URLs carry their size ("…/100x100bb.jpg"): ask for 600 px. */
    internal fun biggerArtwork(url: String): String {
        for (small in arrayOf("100x100bb", "60x60bb", "30x30bb", "170x170bb", "55x55bb")) {
            val at = url.lastIndexOf(small)
            if (at >= 0) return url.substring(0, at) + "600x600bb" + url.substring(at + small.length)
        }
        return url
    }

    /** iTunes search / lookup (podcasts with a feed). Malformed JSON throws; entries of the wrong shape are skipped. */
    fun parseSearch(body: String): List<DirectoryEntry> {
        val j = parseJsonBody(body)
        if (!j.isObject || !j["results"].isArray) throw PodcastError("podcast directory: no results")
        val out = ArrayList<DirectoryEntry>()
        val seen = HashSet<String>()
        for (r in j["results"].arr) {
            if (!r.isObject) continue
            val kind = jstr(r, "kind")
            val wrapper = jstr(r, "wrapperType")
            if (kind.isNotEmpty() && kind != "podcast") continue
            if (kind.isEmpty() && wrapper != "track" && wrapper != "collection") continue
            val title = oneLine(jstr(r, "collectionName")).ifEmpty { oneLine(jstr(r, "trackName")) }
            val feedUrl = trimXml(jstr(r, "feedUrl"))
            var image = ""
            for (key in arrayOf("artworkUrl600", "artworkUrl100", "artworkUrl60")) {
                val a = trimXml(jstr(r, key))
                if (PodcastText.isHttpUrl(a)) {
                    image = biggerArtwork(a)
                    break
                }
            }
            val e = DirectoryEntry(
                appleId = jstr(r, "collectionId").ifEmpty { jstr(r, "trackId") },
                title = title,
                author = oneLine(jstr(r, "artistName")),
                image = image,
                feedUrl = feedUrl,
                genre = jstr(r, "primaryGenreName"),
            )
            if (e.title.isEmpty() || !PodcastText.isHttpUrl(e.feedUrl) || !seen.add(e.feedUrl)) continue
            out.add(e)
        }
        return out
    }

    /** rss.applemarketingtools.com (and the legacy itunes.apple.com/<cc>/rss/toppodcasts) top lists. */
    fun parseCharts(body: String): List<DirectoryEntry> {
        val j = parseJsonBody(body)
        if (!j.isObject || !j["feed"].isObject) throw PodcastError("podcast charts: no feed")
        val feed = j["feed"]
        val out = ArrayList<DirectoryEntry>()
        val seen = HashSet<String>()
        if (feed["results"].isArray) { // rss.applemarketingtools.com
            for (r in feed["results"].arr) {
                if (!r.isObject) continue
                val a = trimXml(jstr(r, "artworkUrl100"))
                val genres = r["genres"]
                val e = DirectoryEntry(
                    appleId = jstr(r, "id"),
                    title = oneLine(jstr(r, "name")),
                    author = oneLine(jstr(r, "artistName")),
                    image = if (PodcastText.isHttpUrl(a)) biggerArtwork(a) else "",
                    genre = if (genres.isArray && genres.arr.isNotEmpty() && genres.arr[0].isObject) jstr(genres.arr[0], "name") else "",
                )
                if (e.appleId.isEmpty() || e.title.isEmpty() || !seen.add(e.appleId)) continue
                out.add(e)
            }
            return out
        }
        if (feed.isObject && feed["entry"] != null) { // legacy itunes.apple.com/<cc>/rss/toppodcasts/json ("label" objects)
            val entry = feed["entry"]
            val entries = if (entry.isArray) entry.arr else listOfNotNull(entry)
            fun label(o: JsonElement?, key: String): String = o[key].takeIf { it.isObject }?.let { jstr(it, "label") } ?: ""
            for (r in entries) {
                if (!r.isObject) continue
                val idAttrs = r["id"].takeIf { it.isObject }?.get("attributes")?.takeIf { it.isObject }
                val images = r["im:image"]
                var image = ""
                if (images.isArray && images.arr.isNotEmpty() && images.arr.last().isObject) {
                    val a = trimXml(jstr(images.arr.last(), "label"))
                    if (PodcastText.isHttpUrl(a)) image = biggerArtwork(a)
                }
                val catAttrs = r["category"].takeIf { it.isObject }?.get("attributes")?.takeIf { it.isObject }
                val e = DirectoryEntry(
                    appleId = if (idAttrs != null) jstr(idAttrs, "im:id") else "",
                    title = oneLine(label(r, "im:name")),
                    author = oneLine(label(r, "im:artist")),
                    image = image,
                    genre = if (catAttrs != null) jstr(catAttrs, "label") else "",
                )
                if (e.appleId.isEmpty() || e.title.isEmpty() || !seen.add(e.appleId)) continue
                out.add(e)
            }
            return out
        }
        throw PodcastError("podcast charts: unknown format")
    }
}
