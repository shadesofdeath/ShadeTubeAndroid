package com.app.shadetube.youtube.explode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

// Ports of YoutubeExplode/src/Utils (StringUtils, Url, Json, Time, Crypto). Internal to the library.

internal object Str {
    fun isAsciiDigit(c: Char) = c in '0'..'9'
    fun isAsciiAlnum(c: Char) = isAsciiDigit(c) || c in 'a'..'z' || c in 'A'..'Z'

    /** ASCII-only lower-casing (the C++ asciiLower). */
    fun toLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') (c + 32) else c)
        return sb.toString()
    }

    fun iequals(a: String, b: String): Boolean = a.length == b.length && toLower(a) == toLower(b)
    fun icontains(s: String, needle: String) = toLower(s).contains(toLower(needle))

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || c == '\u000B'

    fun trim(s: CharSequence): String {
        var b = 0
        var e = s.length
        while (b < e && isSpace(s[b])) ++b
        while (e > b && isSpace(s[e - 1])) --e
        return s.subSequence(b, e).toString()
    }

    fun isBlank(s: String) = trim(s).isEmpty()

    /** Like the C++ split: always at least one part, empty parts kept. */
    fun split(s: String, delimiter: Char): List<String> = s.split(delimiter)

    fun replaceAll(s: String, from: String, to: String) = if (from.isEmpty()) s else s.replace(from, to)

    /** Part of [s] before the first occurrence of [sep] (whole string if not found). */
    fun substringUntil(s: String, sep: String): String {
        val p = s.indexOf(sep)
        return if (p < 0) s else s.substring(0, p)
    }

    /** Part of [s] after the first occurrence of [sep] (empty if not found). */
    fun substringAfter(s: String, sep: String): String {
        val p = s.indexOf(sep)
        return if (p < 0) "" else s.substring(p + sep.length)
    }

    fun stripNonDigits(s: String) = s.filter { isAsciiDigit(it) }

    fun tryParseInt64(s: String): Long? {
        val t = trim(s)
        if (t.isEmpty()) return null
        // from_chars: optional '-', digits only.
        val body = if (t.startsWith('-')) t.substring(1) else t
        if (body.isEmpty() || !body.all { isAsciiDigit(it) }) return null
        return t.toLongOrNull()
    }

    fun tryParseInt(s: String): Int? {
        val v = tryParseInt64(s) ?: return null
        return if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) null else v.toInt()
    }

    fun tryParseDouble(s: String): Double? {
        val t = trim(s)
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()
    }

    /** Parses "m:ss", "mm:ss", "h:mm:ss" into seconds. */
    fun tryParseClockDuration(s: String): Long? {
        val parts = split(trim(s), ':')
        if (parts.size < 2 || parts.size > 3) return null
        var total = 0L
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 2 + (if (i == 0) 3 else 0)) return null
            val v = tryParseInt64(p) ?: return null
            if (v < 0) return null
            if (i > 0 && v >= 60) return null
            total = total * 60 + v
        }
        return total
    }

    /** Decodes the handful of HTML entities that appear in YouTube pages (&amp; &quot; &#39; &#x27; ...). */
    fun htmlDecode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '&') {
                out.append(ch); i++; continue
            }
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 10) {
                out.append(ch); i++; continue
            }
            val entity = s.substring(i + 1, semi)
            var handled = true
            when {
                entity == "amp" -> out.append('&')
                entity == "lt" -> out.append('<')
                entity == "gt" -> out.append('>')
                entity == "quot" -> out.append('"')
                entity == "apos" -> out.append('\'')
                entity == "nbsp" -> out.append(' ')
                entity.isNotEmpty() && entity[0] == '#' -> {
                    val hex = entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')
                    val digits = entity.substring(if (hex) 2 else 1)
                    val cp = if (digits.isEmpty()) null else digits.toLongOrNull(if (hex) 16 else 10)
                    if (cp == null || cp < 0 || cp > 0x10FFFF || digits.startsWith('+') || digits.startsWith('-')) handled = false
                    else out.appendCodePoint(cp.toInt())
                }
                else -> handled = false
            }
            if (handled) i = semi + 1 else { out.append('&'); i++ }
        }
        return out.toString()
    }
}

/** URL helpers (Uri.EscapeDataString / WebUtility.UrlDecode semantics). */
internal object Url {
    private const val HEX = "0123456789ABCDEF"

    /** Percent-encodes everything except RFC 3986 unreserved characters. */
    fun encode(value: String): String {
        val sb = StringBuilder(value.length * 3)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (Str.isAsciiAlnum(ch) || ch == '-' || ch == '_' || ch == '.' || ch == '~')) sb.append(ch)
            else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
        return sb.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Decodes percent-escapes and '+' as space. */
    fun decode(value: String): String {
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '+') {
                out.write(' '.code)
            } else if (c == '%' && i + 2 < value.length) {
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 2
                } else {
                    out.write('%'.code)
                }
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                if (Character.isHighSurrogate(c) && i + 1 < value.length) {
                    out.write(value.substring(i, i + 2).toByteArray(Charsets.UTF_8))
                    i++
                } else {
                    out.write(bytes)
                }
            }
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun queryOf(urlOrQuery: String): String {
        val q = urlOrQuery.indexOf('?')
        val query = if (q < 0) urlOrQuery else urlOrQuery.substring(q + 1)
        val hash = query.indexOf('#')
        return if (hash < 0) query else query.substring(0, hash)
    }

    /** Parses "a=1&b=2" or a full URL's query into ordered key/value pairs. */
    fun getQueryParameters(urlOrQuery: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        for (parameter in Str.split(queryOf(urlOrQuery), '&')) {
            val eq = parameter.indexOf('=')
            val key = decode(if (eq < 0) parameter else parameter.substring(0, eq))
            val value = if (eq < 0) "" else decode(parameter.substring(eq + 1))
            if (Str.isBlank(key)) continue
            result.add(key to value)
        }
        return result
    }

    fun tryGetQueryParameter(urlOrQuery: String, key: String): String? =
        getQueryParameters(urlOrQuery).firstOrNull { it.first == key }?.second

    fun containsQueryParameter(url: String, key: String) = tryGetQueryParameter(url, key) != null

    fun removeQueryParameter(url: String, key: String): String {
        val q = url.indexOf('?')
        if (q < 0 || !containsQueryParameter(url, key)) return url
        val hashPos = url.indexOf('#', q)
        val fragment = if (hashPos < 0) "" else url.substring(hashPos)
        val base = url.substring(0, q)
        val query = StringBuilder()
        for ((k, v) in getQueryParameters(url)) {
            if (k == key) continue
            query.append(if (query.isEmpty()) '?' else '&')
            query.append(encode(k)).append('=').append(encode(v))
        }
        return base + query + fragment
    }

    fun setQueryParameter(url: String, key: String, value: String): String {
        var without = removeQueryParameter(url, key)
        val hashPos = without.indexOf('#')
        val fragment = if (hashPos < 0) "" else without.substring(hashPos)
        if (hashPos >= 0) without = without.substring(0, hashPos)
        val hasQuery = without.indexOf('?') >= 0
        return without + (if (hasQuery) "&" else "?") + encode(key) + "=" + encode(value) + fragment
    }

    private fun authorityOf(url: String): String {
        val scheme = url.indexOf("://")
        val start = if (scheme < 0) 0 else scheme + 3
        var end = -1
        for (i in start until url.length) if (url[i] == '/' || url[i] == '?' || url[i] == '#') { end = i; break }
        return if (end < 0) url.substring(start) else url.substring(start, end)
    }

    /** Host part of an absolute URL ("www.youtube.com"), lower-cased. */
    fun getHost(url: String): String {
        var authority = authorityOf(url)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        val colon = authority.indexOf(':')
        return Str.toLower(if (colon < 0) authority else authority.substring(0, colon))
    }

    /** Path part of an absolute URL ("/youtubei/v1/player"), without the query. */
    fun getPath(url: String): String {
        val scheme = url.indexOf("://")
        val start = if (scheme < 0) 0 else scheme + 3
        val slash = url.indexOf('/', start)
        if (slash < 0) return "/"
        var end = -1
        for (i in slash until url.length) if (url[i] == '?' || url[i] == '#') { end = i; break }
        return if (end < 0) url.substring(slash) else url.substring(slash, end)
    }

    /** "https://www.youtube.com" */
    fun getOrigin(url: String): String {
        val scheme = url.indexOf("://")
        val s = if (scheme < 0) "https" else url.substring(0, scheme)
        return s + "://" + authorityOf(url)
    }
}

// ---- JSON (nlohmann-style null-safe navigation; strictly typed like the C++ jsonString / jsonInt64 ...) -------------

/** Null-safe navigation: String keys and Int indices; null if any step is missing or of the wrong type. */
internal fun dig(node: JsonElement?, vararg path: Any): JsonElement? {
    var cur = node
    for (p in path) {
        cur = when (p) {
            is String -> (cur as? JsonObject)?.get(p)
            is Int -> (cur as? JsonArray)?.getOrNull(p)
            else -> null
        } ?: return null
    }
    return cur
}

private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

/** A JSON string (not a number or bool). */
internal fun jsonString(node: JsonElement?): String? = node.primitiveOrNull()?.takeIf { it.isString }?.content

/** Accepts numbers and numeric strings (YouTube uses both). */
internal fun jsonInt64(node: JsonElement?): Long? {
    val p = node.primitiveOrNull() ?: return null
    if (p.isString) return Str.tryParseInt64(p.content)
    p.longOrNull?.let { return it }
    return p.doubleOrNull?.toLong()
}

internal fun jsonInt(node: JsonElement?): Int? = jsonInt64(node)?.toInt()

/** Accepts numbers and numeric strings. */
internal fun jsonDouble(node: JsonElement?): Double? {
    val p = node.primitiveOrNull() ?: return null
    if (p.isString) {
        val s = p.content
        return if (s.isNotEmpty()) s.toDoubleOrNull() else null
    }
    if (p.booleanOrNull != null) return null
    return p.doubleOrNull
}

internal fun jsonBool(node: JsonElement?): Boolean? {
    val p = node.primitiveOrNull() ?: return null
    if (p.isString) return null
    return p.booleanOrNull
}

/**
 * Extracts text from YouTube's text containers: {"simpleText": ...}, {"runs": [{"text": ...}]} or {"content": ...}.
 * Plain strings are returned as-is.
 */
internal fun jsonText(node: JsonElement?): String? {
    if (node == null) return null
    jsonString(node)?.let { return it }
    jsonString(dig(node, "simpleText"))?.let { return it }
    val runs = dig(node, "runs")
    if (runs is JsonArray) {
        val text = StringBuilder()
        var any = false
        for (run in runs) {
            jsonString(dig(run, "text"))?.let { text.append(it); any = true }
        }
        if (any) return text.toString()
    }
    return jsonString(dig(node, "content"))
}

private fun collect(node: JsonElement, key: String, out: MutableList<JsonElement>, firstOnly: Boolean) {
    if (firstOnly && out.isNotEmpty()) return
    when (node) {
        is JsonObject -> for ((k, v) in node) {
            if (k == key) {
                out.add(v)
                if (firstOnly) return
            }
            collect(v, key, out, firstOnly)
            if (firstOnly && out.isNotEmpty()) return
        }
        is JsonArray -> for (child in node) {
            collect(child, key, out, firstOnly)
            if (firstOnly && out.isNotEmpty()) return
        }
        else -> Unit
    }
}

/** Every value stored under [key] anywhere below [root] (depth-first, document order). */
internal fun findDescendants(root: JsonElement, key: String): List<JsonElement> =
    ArrayList<JsonElement>().also { collect(root, key, it, false) }

internal fun findFirstDescendant(root: JsonElement, key: String): JsonElement? =
    ArrayList<JsonElement>().also { collect(root, key, it, true) }.firstOrNull()

/** Parses JSON, throwing YoutubeExplodeException on malformed input. */
internal fun parseJson(source: String): JsonElement = try {
    Json.parseToJsonElement(source)
} catch (e: Exception) {
    throw YoutubeExplodeException("Failed to parse JSON response: ${e.message}")
}

internal fun tryParseJson(source: String): JsonElement? = runCatching { Json.parseToJsonElement(source) }.getOrNull()

/**
 * Given text that starts with a JSON object/array (possibly followed by other content), returns only the JSON value by
 * matching braces while respecting strings.
 */
internal fun extractJson(source: String): String {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in source.indices) {
        val ch = source[i]
        if (inString) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') inString = false
            continue
        }
        if (ch == '"') inString = true
        else if (ch == '{' || ch == '[') ++depth
        else if (ch == '}' || ch == ']') {
            --depth
            if (depth == 0) return source.substring(0, i + 1)
        }
    }
    return source
}

// ---- Time ------------------------------------------------------------------------------------------------------------

/** ISO-8601 helpers; instants are epoch milliseconds (UTC). */
object Iso8601 {
    private fun daysFromCivil(yIn: Long, m: Int, d: Int): Long {
        val y = yIn - if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private val pattern =
        Regex("""^\s*(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(Z|[+-]\d{2}:?\d{2})?\s*$""")

    /** Parses "YYYY-MM-DD", "YYYY-MM-DDThh:mm:ss", with an optional "Z" or "+hh:mm" suffix. Epoch ms. */
    fun tryParse(value: String): Long? {
        val m = pattern.matchEntire(value) ?: return null
        fun num(i: Int) = m.groups[i]?.value?.toLong() ?: 0L
        val days = daysFromCivil(num(1), num(2).toInt(), num(3).toInt())
        var secs = days * 86400 + num(4) * 3600 + num(5) * 60 + num(6)
        val tzGroup = m.groups[7]?.value
        if (tzGroup != null && tzGroup != "Z") {
            val tz = tzGroup.replace(":", "")
            val sign = if (tz[0] == '-') -1 else 1
            val offset = tz.substring(1, 3).toLong() * 3600 + tz.substring(3, 5).toLong() * 60
            secs -= sign * offset
        }
        return secs * 1000
    }

    /** "2009-10-25T06:57:33Z" */
    fun format(epochMs: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(Math.floorDiv(epochMs, 1000L) * 1000))
    }

    /** "h:mm:ss" (or "m:ss" when shorter than one hour). */
    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return if (total >= 3600) "%d:%02d:%02d".format(Locale.US, total / 3600, (total / 60) % 60, total % 60)
        else "%d:%02d".format(Locale.US, total / 60, total % 60)
    }
}

// ---- Crypto ----------------------------------------------------------------------------------------------------------

internal object Crypto {
    /** SHA-1 digest as upper-case hex (used for the SAPISIDHASH authorization header). */
    fun sha1Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(data.toByteArray(Charsets.UTF_8))
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(40)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v shr 4]).append(hex[v and 15])
        }
        return sb.toString()
    }

    /** Decodes standard or URL-safe base64 (padding optional). */
    fun base64Decode(input: String): ByteArray? {
        fun value(c: Char): Int = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '+', '-' -> 62
            '/', '_' -> 63
            else -> -1
        }
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input) {
            if (c == '=' || c == '\n' || c == '\r') continue
            val v = value(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
            buffer = buffer and 0xFFFF
        }
        return out.toByteArray()
    }
}
