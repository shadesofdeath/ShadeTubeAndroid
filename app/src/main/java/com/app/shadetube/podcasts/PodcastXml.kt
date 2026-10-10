package com.app.shadetube.podcasts

/**
 * A small non-validating XML reader for feeds (port of the XmlReader in app/Podcasts.cpp): elements, attributes,
 * character data with CDATA and entities (the XML ones, numeric references and the HTML names feeds use without
 * declaring them), comments, processing instructions, a DOCTYPE, a UTF-8 / UTF-16 BOM and Latin-1 / Windows-1252
 * documents. Names keep their prefix, but a prefix bound to a namespace feeds use is renamed to its usual one
 * ("itunes:", "content:", "podcast:", "media:", "atom:", "googleplay:", "dc:") whatever the document called it.
 * Markup that can't be read throws [PodcastError]; elements still open at the end are closed.
 *
 * Android's XmlPullParser is not used on purpose: it is strict (a bare '&' or an undeclared HTML entity, both common in
 * real feeds, abort the parse) and it is a stub in JVM unit tests.
 */
class XmlNode internal constructor(name: String = "") {
    var name: String = name
        internal set
    val attrs: MutableList<Pair<String, String>> = ArrayList(2)
    internal val textBuilder = StringBuilder()
    val children: MutableList<XmlNode> = ArrayList()

    /** Character data directly inside this element (CDATA included), in order. */
    val text: String get() = textBuilder.toString()

    /** First child element with this name. */
    fun child(n: String): XmlNode? = children.firstOrNull { it.name == n }

    /** The first such child's text, trimmed ("" = none). */
    fun childText(n: String): String = child(n)?.let { trimXml(it.textBuilder) } ?: ""

    /** "" = none. */
    fun attr(n: String): String = attrs.firstOrNull { it.first == n }?.second ?: ""
}

/** A document node whose children are the top-level elements. [document] is raw bytes (any supported encoding). */
fun parseXml(document: ByteArray): XmlNode = XmlReader(documentText(document)).parse()

/** Same for text that is already decoded (a leading U+FEFF is dropped). */
fun parseXml(document: String): XmlNode = XmlReader(document.removePrefix("\uFEFF")).parse()

// ---- small string helpers ---------------------------------------------------------------------------------------------

internal fun isXmlSpace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

internal fun trimXml(s: CharSequence): String {
    var b = 0
    var e = s.length
    while (b < e && isXmlSpace(s[b])) ++b
    while (e > b && isXmlSpace(s[e - 1])) --e
    return s.subSequence(b, e).toString()
}

internal fun asciiLower(s: String): String {
    if (s.none { it in 'A'..'Z' }) return s
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
    return sb.toString()
}

internal fun isAsciiAlpha(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
internal fun isAsciiDigit(c: Char) = c in '0'..'9'
internal fun isAsciiAlnum(c: Char) = isAsciiAlpha(c) || isAsciiDigit(c)

/** Collapses runs of whitespace into one space and trims (titles, names). */
internal fun oneLine(s: String): String {
    val out = StringBuilder(s.length)
    var space = false
    for (c in s) {
        if (isXmlSpace(c)) {
            space = out.isNotEmpty()
            continue
        }
        if (space) out.append(' ')
        space = false
        out.append(c)
    }
    return out.toString()
}

/** Cuts so that the UTF-8 form is at most [max] bytes (at a character boundary) and appends "…" when cut. */
internal fun capUtf8(s: String, max: Int): String {
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val len = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + len > max) return s.substring(0, i) + "…"
        bytes += len
        i += Character.charCount(cp)
    }
    return s
}

/** C's atoi64: leading spaces, a sign, digits; anything else ends it. */
internal fun atoi64(s: String): Long {
    var i = 0
    while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) ++i
    var neg = false
    if (i < s.length && (s[i] == '+' || s[i] == '-')) {
        neg = s[i] == '-'
        ++i
    }
    var v = 0L
    while (i < s.length && isAsciiDigit(s[i])) {
        v = if (v > (Long.MAX_VALUE - 9) / 10) Long.MAX_VALUE else v * 10 + (s[i] - '0')
        ++i
    }
    return if (neg) -v else v
}

internal fun atoi(s: String): Int = atoi64(s).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

// ---- entities ----------------------------------------------------------------------------------------------------------

/** HTML names of U+00A0 .. U+00FF, in order. */
private val LATIN1_NAMES = arrayOf(
    "nbsp", "iexcl", "cent", "pound", "curren", "yen", "brvbar", "sect", "uml", "copy", "ordf", "laquo",
    "not", "shy", "reg", "macr", "deg", "plusmn", "sup2", "sup3", "acute", "micro", "para", "middot",
    "cedil", "sup1", "ordm", "raquo", "frac14", "frac12", "frac34", "iquest", "Agrave", "Aacute", "Acirc", "Atilde",
    "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml", "Igrave", "Iacute", "Icirc", "Iuml",
    "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "times", "Oslash", "Ugrave", "Uacute", "Ucirc",
    "Uuml", "Yacute", "THORN", "szlig", "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil",
    "egrave", "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml", "eth", "ntilde", "ograve", "oacute",
    "ocirc", "otilde", "ouml", "divide", "oslash", "ugrave", "uacute", "ucirc", "uuml", "yacute", "thorn", "yuml",
)

private val NAMED_ENTITIES: Map<String, Int> = hashMapOf(
    "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
    "ndash" to 0x2013, "mdash" to 0x2014, "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A,
    "ldquo" to 0x201C, "rdquo" to 0x201D, "bdquo" to 0x201E, "dagger" to 0x2020, "Dagger" to 0x2021,
    "bull" to 0x2022, "hellip" to 0x2026, "permil" to 0x2030, "lsaquo" to 0x2039, "rsaquo" to 0x203A,
    "euro" to 0x20AC, "trade" to 0x2122, "OElig" to 0x0152, "oelig" to 0x0153, "Scaron" to 0x0160,
    "scaron" to 0x0161, "Yuml" to 0x0178, "fnof" to 0x0192, "circ" to 0x02C6, "tilde" to 0x02DC,
    "ensp" to 0x2002, "emsp" to 0x2003, "thinsp" to 0x2009, "zwnj" to 0x200C, "zwj" to 0x200D,
    "lrm" to 0x200E, "rlm" to 0x200F, "prime" to 0x2032, "Prime" to 0x2033, "larr" to 0x2190,
    "rarr" to 0x2192, "uarr" to 0x2191, "darr" to 0x2193, "hearts" to 0x2665, "star" to 0x2606,
).also { m -> LATIN1_NAMES.forEachIndexed { i, n -> m[n] = 0xA0 + i } }

/** Windows-1252 code points 0x80..0x9F (numeric references in that range mean these, as in browsers). */
internal val CP1252_HIGH = intArrayOf(
    0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160,
    0x2039, 0x0152, 0x8D, 0x017D, 0x8F, 0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022,
    0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
)

/**
 * The entity starting right after the '&' at [from]: its code point, with the characters consumed (including ';')
 * in [len] [0]; len 0 = not an entity.
 */
internal fun entityAt(s: CharSequence, from: Int, len: IntArray, to: Int = s.length): Int {
    len[0] = 0
    var semi = -1
    var k = from
    while (k < to && k <= from + 32) { // a ";" further away makes no entity anyway
        if (s[k] == ';') {
            semi = k
            break
        }
        ++k
    }
    if (semi < 0) return 0
    val rel = semi - from
    if (rel == 0 || rel > 32) return 0
    if (s[from] == '#') {
        val hex = rel > 1 && (s[from + 1] == 'x' || s[from + 1] == 'X')
        val start = from + if (hex) 2 else 1
        if (start >= semi) return 0
        var cp = 0L
        for (i in start until semi) {
            val c = s[i]
            val v = when {
                c in '0'..'9' -> c - '0'
                hex && c in 'a'..'f' -> c - 'a' + 10
                hex && c in 'A'..'F' -> c - 'A' + 10
                else -> return 0
            }
            cp = cp * (if (hex) 16 else 10) + v
            if (cp > 0x10FFFF) cp = 0x110000 // stays invalid (replaced)
        }
        var code = cp.toInt()
        if (code in 0x80..0x9F) code = CP1252_HIGH[code - 0x80]
        len[0] = rel + 1
        return code
    }
    val cp = NAMED_ENTITIES[s.subSequence(from, semi).toString()] ?: return 0
    len[0] = rel + 1
    return cp
}

internal fun appendCodePoint(out: StringBuilder, cpIn: Int) {
    val cp = if (cpIn == 0 || cpIn in 0xD800..0xDFFF || cpIn > 0x10FFFF || cpIn < 0) 0xFFFD else cpIn
    out.appendCodePoint(cp)
}

internal fun decodeEntities(s: CharSequence, from: Int = 0, to: Int = s.length, out: StringBuilder = StringBuilder()): StringBuilder {
    val len = IntArray(1)
    var i = from
    while (i < to) {
        val c = s[i]
        if (c != '&') {
            out.append(c)
            ++i
            continue
        }
        val cp = entityAt(s, i + 1, len, to)
        if (len[0] == 0) { // a bare '&' (common in sloppy feeds): kept as it is
            out.append('&')
            ++i
            continue
        }
        appendCodePoint(out, cp)
        i += len[0] + 1
    }
    return out
}

internal fun decodeEntities(s: String): String =
    if (s.indexOf('&') < 0) s else decodeEntities(s, 0, s.length).toString()

// ---- encodings -----------------------------------------------------------------------------------------------------------

internal fun validUtf8(b: ByteArray, from: Int = 0, to: Int = b.size): Boolean {
    var i = from
    while (i < to) {
        val c = b[i].toInt() and 0xFF
        if (c < 0x80) {
            ++i
            continue
        }
        val extra = when {
            (c and 0xE0) == 0xC0 && c >= 0xC2 -> 1
            (c and 0xF0) == 0xE0 -> 2
            (c and 0xF8) == 0xF0 && c <= 0xF4 -> 3
            else -> return false
        }
        if (i + extra >= to) return false
        for (k in 1..extra) if ((b[i + k].toInt() and 0xC0) != 0x80) return false
        i += extra + 1
    }
    return true
}

/** Windows-1252 as Windows decodes it (the five undefined bytes map to the same C1 code points). */
internal fun decodeCp1252(b: ByteArray, from: Int = 0, to: Int = b.size): String {
    val sb = StringBuilder(to - from)
    for (i in from until to) {
        val c = b[i].toInt() and 0xFF
        sb.append(if (c in 0x80..0x9F) CP1252_HIGH[c - 0x80].toChar() else c.toChar())
    }
    return sb.toString()
}

/**
 * The document as text: BOMs removed, UTF-16 converted, and bytes that aren't valid UTF-8 read as Windows-1252 (what
 * feeds declaring ISO-8859-1 / windows-1252 really contain). A declaration of Latin-1 over valid UTF-8 is a mistake
 * feeds often make: valid UTF-8 always wins.
 */
internal fun documentText(doc: ByteArray): String {
    var start = 0
    if (doc.size >= 3 && doc[0] == 0xEF.toByte() && doc[1] == 0xBB.toByte() && doc[2] == 0xBF.toByte()) start = 3
    if (doc.size - start >= 2) {
        val b0 = doc[start].toInt() and 0xFF
        val b1 = doc[start + 1].toInt() and 0xFF
        if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
            val units = (doc.size - start - 2) / 2
            val cs = if (b0 == 0xFF) Charsets.UTF_16LE else Charsets.UTF_16BE
            return String(doc, start + 2, units * 2, cs)
        }
    }
    if (validUtf8(doc, start, doc.size)) return String(doc, start, doc.size - start, Charsets.UTF_8)
    return decodeCp1252(doc, start, doc.size)
}

// ---- namespaces ----------------------------------------------------------------------------------------------------------

/** The prefix feeds conventionally use for a namespace URI (null = not one we read). */
internal fun canonicalPrefix(uri: String): String? {
    var u = asciiLower(trimXml(uri))
    for (scheme in arrayOf("https://", "http://")) {
        if (u.startsWith(scheme)) {
            u = u.substring(scheme.length)
            break
        }
    }
    u = u.trimEnd('/')
    return when (u) {
        "www.itunes.com/dtds/podcast-1.0.dtd", "itunes.com/dtds/podcast-1.0.dtd" -> "itunes"
        "purl.org/rss/1.0/modules/content" -> "content"
        "podcastindex.org/namespace/1.0", "github.com/podcastindex-org/podcast-namespace/blob/main/docs/1.0.md" -> "podcast"
        "search.yahoo.com/mrss" -> "media"
        "www.w3.org/2005/atom" -> "atom"
        "www.google.com/schemas/play-podcasts/1.0" -> "googleplay"
        "purl.org/dc/elements/1.1" -> "dc"
        else -> null
    }
}

// ---- reader ----------------------------------------------------------------------------------------------------------------

private const val MAX_DEPTH = 256

private class XmlReader(private val s: String) {
    private class Binding(val depth: Int, val prefix: String, val uri: String)

    private val bindings = ArrayList<Binding>()

    fun parse(): XmlNode {
        val doc = XmlNode()
        val stack = ArrayList<XmlNode>().apply { add(doc) }
        val n = s.length
        var i = 0
        while (i < n) {
            if (s[i] != '<') {
                var lt = s.indexOf('<', i)
                if (lt < 0) lt = n
                appendText(stack.last(), i, lt)
                i = lt
                continue
            }
            if (s.startsWith("<!--", i)) {
                val e = s.indexOf("-->", i + 4)
                if (e < 0) throw PodcastError("XML: unterminated comment")
                i = e + 3
                continue
            }
            if (s.startsWith("<![CDATA[", i)) {
                val e = s.indexOf("]]>", i + 9)
                if (e < 0) throw PodcastError("XML: unterminated CDATA")
                stack.last().textBuilder.append(s, i + 9, e)
                i = e + 3
                continue
            }
            if (s.startsWith("<?", i)) {
                val e = s.indexOf("?>", i + 2)
                if (e < 0) throw PodcastError("XML: unterminated processing instruction")
                i = e + 2
                continue
            }
            if (s.startsWith("<!", i)) { // DOCTYPE (with an internal subset) and other declarations
                var j = i + 2
                var depth = 0
                while (j < n) {
                    val c = s[j]
                    if (c == '[') ++depth else if (c == ']') --depth else if (c == '>' && depth <= 0) break
                    ++j
                }
                if (j >= n) throw PodcastError("XML: unterminated declaration")
                i = j + 1
                continue
            }
            if (s.startsWith("</", i)) {
                val e = s.indexOf('>', i + 2)
                if (e < 0) throw PodcastError("XML: unterminated end tag")
                close(stack, trimXml(s.subSequence(i + 2, e)))
                i = e + 1
                continue
            }
            i = startTag(stack, i)
        }
        return doc
    }

    private fun appendText(node: XmlNode, from: Int, to: Int) {
        // Whitespace between elements carries nothing (childText() trims anyway): skip it to keep nodes small.
        if (node.textBuilder.isEmpty()) {
            var allSpace = true
            for (k in from until to) if (!isXmlSpace(s[k])) {
                allSpace = false
                break
            }
            if (allSpace) return
        }
        decodeEntities(s, from, to, node.textBuilder)
    }

    private fun qualified(raw: String): String {
        val colon = raw.indexOf(':')
        if (colon < 0) return raw
        val prefix = raw.substring(0, colon)
        for (k in bindings.indices.reversed()) {
            val b = bindings[k]
            if (b.prefix == prefix) {
                val canon = canonicalPrefix(b.uri)
                if (canon != null) return canon + raw.substring(colon)
                break
            }
        }
        return raw
    }

    private fun close(stack: ArrayList<XmlNode>, rawName: String) {
        val name = qualified(rawName)
        for (k in stack.size - 1 downTo 1) {
            if (stack[k].name != name) continue
            while (stack.size > k) stack.removeAt(stack.size - 1) // unclosed children end here too
            popBindings(stack.size)
            return
        }
        // A stray end tag: ignored.
    }

    private fun popBindings(depth: Int) {
        while (bindings.isNotEmpty() && bindings.last().depth >= depth) bindings.removeAt(bindings.size - 1)
    }

    private fun startTag(stack: ArrayList<XmlNode>, i: Int): Int {
        val n = s.length
        var j = i + 1
        while (j < n && !isXmlSpace(s[j]) && s[j] != '>' && s[j] != '/') ++j
        val rawName = s.substring(i + 1, j)
        if (rawName.isEmpty() || !(isAsciiAlpha(rawName[0]) || rawName[0] == '_' || rawName[0].code >= 0x80)) {
            stack.last().textBuilder.append('<') // "a < b" in sloppy text
            return i + 1
        }
        val node = XmlNode()
        var selfClosing = false
        val depth = stack.size
        while (true) {
            while (j < n && isXmlSpace(s[j])) ++j
            if (j >= n) throw PodcastError("XML: unterminated start tag")
            if (s[j] == '>') {
                ++j
                break
            }
            if (s[j] == '/') {
                if (j + 1 < n && s[j + 1] == '>') {
                    selfClosing = true
                    j += 2
                    break
                }
                ++j
                continue
            }
            var k = j
            while (k < n && !isXmlSpace(s[k]) && s[k] != '=' && s[k] != '>' && s[k] != '/') ++k
            val attrName = s.substring(j, k)
            j = k
            while (j < n && isXmlSpace(s[j])) ++j
            var value = ""
            if (j < n && s[j] == '=') {
                ++j
                while (j < n && isXmlSpace(s[j])) ++j
                if (j >= n) throw PodcastError("XML: unterminated attribute")
                if (s[j] == '"' || s[j] == '\'') {
                    val e = s.indexOf(s[j], j + 1)
                    if (e < 0) throw PodcastError("XML: unterminated attribute value")
                    value = decodeEntities(s.substring(j + 1, e))
                    j = e + 1
                } else {
                    var e = j
                    while (e < n && !isXmlSpace(s[e]) && s[e] != '>') ++e
                    value = decodeEntities(s.substring(j, e))
                    j = e
                }
            }
            if (attrName.isEmpty()) continue
            if (attrName.startsWith("xmlns:")) bindings.add(Binding(depth, attrName.substring(6), value))
            node.attrs.add(attrName to value)
        }
        node.name = qualified(rawName)
        stack.last().children.add(node)
        if (selfClosing) {
            popBindings(depth)
        } else {
            if (stack.size >= MAX_DEPTH) throw PodcastError("XML: nested too deeply")
            stack.add(node)
        }
        return j
    }
}
