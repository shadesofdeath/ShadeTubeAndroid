package com.app.shadetube.stats

import com.app.shadetube.catalog.ArtistRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * listening.json / listening-imported.json reading and writing.
 *
 * The files hold up to 100 000 (this device) and 2 000 000 (imported) plays, so neither side builds a JSON tree for
 * the plays: the writer streams them into a StringBuilder (C++ putInt / putString) and the reader is a small strict
 * parser that collects the "plays" array straight into primitive arrays (a kotlinx JsonElement per number would cost
 * hundreds of MB on a phone). Tracks are few and parse into ordinary JsonElements.
 */
internal object ListenJson {
    // --- writing -------------------------------------------------------------------------------------------------

    /**
     * JSON string with UTF-16 validation: a lone surrogate becomes U+FFFD, so the file always parses (the C++ version
     * does the same for invalid UTF-8).
     */
    fun putString(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' || c == '\\' -> out.append('\\').append(c)
                c.code < 0x20 -> {
                    val hex = "0123456789abcdef"
                    out.append("\\u00").append(hex[c.code shr 4]).append(hex[c.code and 15])
                }
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                        out.append(c).append(s[i + 1])
                        i++
                    } else {
                        out.append('�')
                    }
                }
                Character.isLowSurrogate(c) -> out.append('�')
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    fun putTrack(out: StringBuilder, t: ListenTrack) {
        out.append("{\"n\":")
        putString(out, t.name)
        if (t.id.isNotEmpty()) {
            out.append(",\"id\":")
            putString(out, t.id)
        }
        if (t.artists.isNotEmpty()) {
            out.append(",\"a\":[")
            for ((i, a) in t.artists.withIndex()) {
                out.append(if (i > 0) ",[" else "[")
                putString(out, a.id)
                out.append(',')
                putString(out, a.name)
                out.append(']')
            }
            out.append(']')
        }
        if (t.albumId.isNotEmpty() || t.albumName.isNotEmpty()) {
            out.append(",\"al\":[")
            putString(out, t.albumId)
            out.append(',')
            putString(out, t.albumName)
            out.append(']')
        }
        if (t.image.isNotEmpty()) {
            out.append(",\"img\":")
            putString(out, t.image)
        }
        if (t.durationMs > 0) out.append(",\"d\":").append(t.durationMs)
        out.append('}')
    }

    fun serialize(tracks: List<ListenTrack>, plays: PlayList, imported: Boolean): String {
        val text = StringBuilder(tracks.size * 200 + plays.size * (if (imported) 18 else 26) + 64)
        text.append(if (imported) "{\"v\":1,\"source\":\"spotify\",\"tracks\":[" else "{\"v\":1,\"tracks\":[")
        for ((i, t) in tracks.withIndex()) {
            if (i > 0) text.append(',')
            putTrack(text, t)
        }
        text.append("],\"plays\":[")
        if (imported) {
            // Flat and delta-coded (the plays are in time order): about 12 bytes a play instead of 26.
            var prev = 0L
            for (i in 0 until plays.size) {
                if (i > 0) text.append(',')
                text.append(plays.track(i)).append(',').append(plays.startedAt(i) - prev).append(',').append(plays.listenedMs(i))
                prev = plays.startedAt(i)
            }
        } else {
            for (i in 0 until plays.size) {
                text.append(if (i > 0) ",[" else "[")
                text.append(plays.track(i)).append(',').append(plays.startedAt(i)).append(',').append(plays.listenedMs(i))
                text.append(']')
            }
        }
        text.append("]}")
        return text.toString()
    }

    // --- reading -------------------------------------------------------------------------------------------------

    private fun str(j: JsonElement?, key: String): String {
        val v = (j as? JsonObject)?.get(key) as? JsonPrimitive ?: return ""
        return if (v.isString) v.content else ""
    }

    private fun isString(e: JsonElement?) = e is JsonPrimitive && e.isString

    /** An integer JSON number (nlohmann's is_number_integer: no fraction, no exponent). */
    internal fun integerOf(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        val c = p.content
        if (c.isEmpty() || c.any { it == '.' || it == 'e' || it == 'E' }) return null
        return c.toLongOrNull()
    }

    fun trackFromJson(j: JsonElement?): ListenTrack {
        val t = ListenTrack()
        if (j !is JsonObject) return t
        t.id = str(j, "id")
        t.name = str(j, "n")
        val a = j["a"]
        if (a is JsonArray) {
            val list = ArrayList<ArtistRef>(a.size)
            for (ar in a)
                if (ar is JsonArray && ar.size == 2 && isString(ar[0]) && isString(ar[1]))
                    list.add(ArtistRef((ar[0] as JsonPrimitive).content, (ar[1] as JsonPrimitive).content))
            t.artists = list
        }
        val al = j["al"]
        if (al is JsonArray && al.size == 2 && isString(al[0]) && isString(al[1])) {
            t.albumId = (al[0] as JsonPrimitive).content
            t.albumName = (al[1] as JsonPrimitive).content
        }
        t.image = str(j, "img")
        integerOf(j["d"])?.let { t.durationMs = maxOf(0L, it) }
        return t
    }

    /** Number kinds as nlohmann classifies them. */
    const val UNSIGNED: Byte = 0
    const val NEGATIVE: Byte = 1
    const val FLOAT: Byte = 2
    const val OTHER: Byte = 3

    /** A parsed history document: its members except "plays", and the plays collected per [Mode]. */
    class Doc {
        var isObject = false
        var hasTracks = false
        var tracks: JsonArray? = null
        var hasPlays = false
        var playsIsArray = false

        /** Mode.Native: the valid [track, startedAt, ms] rows (others skipped, as the C++ does). */
        val native = PlayList(0)

        /** Mode.Flat: every element's value and kind. */
        var flatValues = LongArray(0)
        var flatKinds = ByteArray(0)
        var flatSize = 0
    }

    enum class Mode { Native, Flat }

    /** Null when the text is not JSON at all. */
    fun parse(text: String, mode: Mode): Doc? = runCatching { Parser(text, mode).document() }.getOrNull()

    private class ParseError : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(val s: String, val mode: Mode) {
        var i = 0
        val doc = Doc()

        fun fail(): Nothing = throw ParseError()

        fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        fun document(): Doc {
            ws()
            if (i < s.length && s[i] == '{') topObject() else value()
            ws()
            if (i != s.length) fail()
            return doc
        }

        fun topObject() {
            doc.isObject = true
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val key = string()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                when (key) {
                    "plays" -> {
                        doc.hasPlays = true
                        if (i < s.length && s[i] == '[') {
                            doc.playsIsArray = true
                            if (mode == Mode.Native) nativePlays() else flatPlays()
                        } else {
                            doc.playsIsArray = false
                            value()
                        }
                    }
                    "tracks" -> {
                        doc.hasTracks = true
                        doc.tracks = value() as? JsonArray
                    }
                    else -> value()
                }
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') {
                    i++
                    return
                }
                fail()
            }
        }

        // Last number parsed by number(): value and kind.
        var numValue = 0L
        var numKind: Byte = OTHER
        var numDouble = 0.0

        fun nativePlays() {
            doc.native.truncate(0)
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return
            }
            val kinds = ByteArray(3)
            val vals = LongArray(3)
            while (true) {
                ws()
                if (i < s.length && s[i] == '[') {
                    // [track, startedAt, ms]: exactly three integers, the track index unsigned.
                    i++
                    var n = 0
                    ws()
                    if (i < s.length && s[i] == ']') {
                        i++
                    } else {
                        while (true) {
                            ws()
                            val k: Byte
                            val v: Long
                            if (i < s.length && (s[i] == '-' || s[i] in '0'..'9')) {
                                number()
                                k = numKind
                                v = numValue
                            } else {
                                value()
                                k = OTHER
                                v = 0
                            }
                            if (n < 3) {
                                kinds[n] = k
                                vals[n] = v
                            }
                            n++
                            ws()
                            if (i >= s.length) fail()
                            if (s[i] == ',') {
                                i++
                                continue
                            }
                            if (s[i] == ']') {
                                i++
                                break
                            }
                            fail()
                        }
                    }
                    if (n == 3 && kinds[0] == UNSIGNED && (kinds[1] == UNSIGNED || kinds[1] == NEGATIVE) &&
                        (kinds[2] == UNSIGNED || kinds[2] == NEGATIVE) && vals[0] in 0..Int.MAX_VALUE
                    ) doc.native.add(vals[0].toInt(), vals[1], vals[2], false)
                } else {
                    value()
                }
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') {
                    i++
                    return
                }
                fail()
            }
        }

        fun flatPlays() {
            var values = LongArray(1024)
            var kinds = ByteArray(1024)
            var n = 0
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') {
                i++
            } else {
                while (true) {
                    ws()
                    val k: Byte
                    val v: Long
                    if (i < s.length && (s[i] == '-' || s[i] in '0'..'9')) {
                        number()
                        k = numKind
                        v = numValue
                    } else {
                        value()
                        k = OTHER
                        v = 0
                    }
                    if (n == values.size) {
                        values = values.copyOf(n * 2)
                        kinds = kinds.copyOf(n * 2)
                    }
                    values[n] = v
                    kinds[n] = k
                    n++
                    ws()
                    if (i >= s.length) fail()
                    if (s[i] == ',') {
                        i++
                        continue
                    }
                    if (s[i] == ']') {
                        i++
                        break
                    }
                    fail()
                }
            }
            doc.flatValues = values
            doc.flatKinds = kinds
            doc.flatSize = n
        }

        fun value(): JsonElement {
            if (i >= s.length) fail()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonPrimitive(string())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                else -> {
                    if (c == '-' || c in '0'..'9') {
                        number()
                        when (numKind) {
                            FLOAT -> JsonPrimitive(numDouble)
                            else -> JsonPrimitive(numValue)
                        }
                    } else fail()
                }
            }
        }

        fun literal(word: String, v: JsonElement): JsonElement {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        fun obj(): JsonObject {
            i++
            val m = LinkedHashMap<String, JsonElement>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return JsonObject(m)
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == '}') {
                    i++
                    return JsonObject(m)
                }
                fail()
            }
        }

        fun arr(): JsonArray {
            i++
            val l = ArrayList<JsonElement>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return JsonArray(l)
            }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') {
                    i++
                    continue
                }
                if (s[i] == ']') {
                    i++
                    return JsonArray(l)
                }
                fail()
            }
        }

        fun number() {
            val start = i
            var neg = false
            if (s[i] == '-') {
                neg = true
                i++
            }
            if (i >= s.length) fail()
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i] in '0'..'9') i++
            } else fail()
            var isFloat = false
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                if (i >= s.length || s[i] !in '0'..'9') fail()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') fail()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val token = s.substring(start, i)
            if (!isFloat) {
                val v = token.toLongOrNull()
                if (v != null) {
                    numValue = v
                    numKind = if (neg) NEGATIVE else UNSIGNED
                    return
                }
                // Beyond int64: nlohmann keeps up to uint64 as unsigned, else a float. Neither is a valid play value.
            }
            numDouble = token.toDouble()
            numValue = numDouble.toLong()
            numKind = FLOAT
        }

        fun string(): String {
            i++ // opening quote
            val start = i
            // Fast path: no escapes.
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    val r = s.substring(start, i)
                    i++
                    return r
                }
                if (c == '\\' || c.code < 0x20) break
                i++
            }
            val sb = StringBuilder().append(s, start, i)
            while (true) {
                if (i >= s.length) fail()
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c.code < 0x20 -> fail()
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail()
                        when (s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) fail()
                                val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: fail()
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
    }
}
