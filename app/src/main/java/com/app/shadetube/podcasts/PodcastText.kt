package com.app.shadetube.podcasts

/**
 * Pure text helpers of the feed parser (port of app/Podcasts.cpp): show notes as text, feed dates, itunes:duration.
 */
object PodcastText {
    /**
     * Show notes as text: tags dropped (paragraphs, breaks and list items become lines), scripts / styles removed,
     * entities decoded, spaces collapsed, at most one blank line in a row.
     *
     * Plain text (no tags) keeps its line breaks; HTML's own whitespace is collapsed and its block tags make the lines.
     */
    fun htmlToText(html: String): String {
        var markup = false
        for (i in 0 until html.length - 1) {
            val nx = html[i + 1]
            if (html[i] == '<' && (isAsciiAlpha(nx) || nx == '/' || nx == '!')) {
                markup = true
                break
            }
        }
        val out = StringBuilder(html.length)
        var pendingBreaks = 0
        var pendingSpace = false
        fun flushBreaks() {
            if (out.isEmpty()) {
                pendingBreaks = 0
                pendingSpace = false
                return
            }
            if (pendingBreaks > 0) {
                while (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
                var have = 0
                var k = out.length - 1
                while (k >= 0 && out[k] == '\n') {
                    ++have
                    --k
                }
                for (x in have until minOf(pendingBreaks, 2)) out.append('\n')
                pendingSpace = false
            } else if (pendingSpace && out[out.length - 1] != '\n' && out[out.length - 1] != ' ') {
                out.append(' ')
            }
            pendingBreaks = 0
            pendingSpace = false
        }

        fun emit(text: CharSequence) {
            flushBreaks()
            out.append(text)
        }

        val n = html.length
        val len = IntArray(1)
        var i = 0
        while (i < n) {
            val c = html[i]
            if (markup && c == '<') {
                if (html.startsWith("<!--", i)) {
                    val e = html.indexOf("-->", i + 4)
                    i = if (e < 0) n else e + 3
                    continue
                }
                val e = html.indexOf('>', i + 1)
                if (e < 0) break // a cut-off tag at the end
                var tagStart = i + 1
                val closing = tagStart < e && html[tagStart] == '/'
                if (closing) ++tagStart
                var k = tagStart
                while (k < e && isAsciiAlnum(html[k])) ++k
                val name = asciiLower(html.substring(tagStart, k))
                i = e + 1
                if (!closing && (name == "script" || name == "style")) {
                    val end = html.indexOf("</$name", i, ignoreCase = true)
                    i = if (end < 0) n else end
                    continue
                }
                // Notes put every line in its own <p> / <div>: those start a line; headings, lists and quotes a block.
                when {
                    name == "br" || name == "p" || name == "div" || name == "tr" -> pendingBreaks = maxOf(pendingBreaks, 1)
                    name == "ul" || name == "ol" || name == "blockquote" || name == "pre" || name == "table" ||
                        name == "section" || name == "article" || name == "hr" ||
                        (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') -> pendingBreaks = maxOf(pendingBreaks, 2)
                    name == "li" -> {
                        pendingBreaks = maxOf(pendingBreaks, 1)
                        if (!closing) emit("• ")
                    }
                    name == "td" || name == "th" -> pendingSpace = true
                }
                continue
            }
            if (c == '&') {
                val cp = entityAt(html, i + 1, len)
                if (len[0] > 0) {
                    i += len[0] + 1
                    if (cp == 0xA0 || cp == ' '.code) {
                        pendingSpace = true
                        continue
                    }
                    val ch = StringBuilder(2)
                    appendCodePoint(ch, cp)
                    emit(ch)
                    continue
                }
            }
            if (c == '\n' && !markup) {
                pendingBreaks = minOf(pendingBreaks + 1, 2)
                ++i
                continue
            }
            if (isXmlSpace(c)) {
                pendingSpace = true
                ++i
                continue
            }
            // A run of ordinary characters.
            var j = i + 1
            while (j < n && !isXmlSpace(html[j]) && html[j] != '&' && !(markup && html[j] == '<')) ++j
            emit(html.subSequence(i, j))
            i = j
        }
        while (out.isNotEmpty() && (out[out.length - 1] == ' ' || out[out.length - 1] == '\n')) out.setLength(out.length - 1)
        return out.toString()
    }

    /** Whether [html] contains markup (the notes are HTML rather than plain text). */
    fun hasMarkup(html: String): Boolean {
        for (i in 0 until html.length - 1) {
            val nx = html[i + 1]
            if (html[i] == '<' && (isAsciiAlpha(nx) || nx == '/' || nx == '!')) return true
        }
        return false
    }

    // ---- dates ---------------------------------------------------------------------------------------------------------

    /** H. Hinnant's days_from_civil. */
    private fun daysFromCivil(yIn: Long, m: Int, d: Int): Long {
        val y = yIn - if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private val MONTHS = arrayOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun monthIndex(s: String): Int {
        if (s.length < 3) return -1
        val p = s.substring(0, 3)
        for (i in 0 until 12) if (p.equals(MONTHS[i], ignoreCase = true)) return i + 1
        return -1
    }

    private val ZONES = listOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0, "EST" to -300,
        "EDT" to -240, "CST" to -360, "CDT" to -300, "MST" to -420, "MDT" to -360,
        "PST" to -480, "PDT" to -420, "AKST" to -540, "AKDT" to -480, "HST" to -600,
        "BST" to 60, "CET" to 60, "CEST" to 120, "EET" to 120, "EEST" to 180,
        "WET" to 0, "WEST" to 60, "MSK" to 180, "AEST" to 600, "AEDT" to 660,
        "JST" to 540, "KST" to 540, "TRT" to 180,
    )

    /** Minutes east of UTC for a named zone; null = not a zone name. */
    private fun zoneOffset(z: String): Int? = ZONES.firstOrNull { it.first.equals(z, ignoreCase = true) }?.second

    private fun allDigits(s: String) = s.isNotEmpty() && s.all { isAsciiDigit(it) }

    /** "+0200", "-05:00", "+02" -> minutes; null when not an offset. */
    private fun numericOffset(z: String): Int? {
        if (z.length < 3 || (z[0] != '+' && z[0] != '-')) return null
        val digits = z.substring(1).replace(":", "")
        if (!allDigits(digits) || (digits.length != 2 && digits.length != 4)) return null
        val h = digits.substring(0, 2).toInt()
        val m = if (digits.length == 4) digits.substring(2).toInt() else 0
        if (h > 14 || m > 59) return null
        return (if (z[0] == '-') -1 else 1) * (h * 60 + m)
    }

    /** "13:00[:00[.fff]]" -> [h, m, s], null when not a clock. */
    private fun parseClock(t: String): IntArray? {
        val parts = IntArray(3)
        var count = 0
        var start = 0
        while (start <= t.length && count < 3) {
            var colon = t.indexOf(':', start)
            if (colon < 0) colon = t.length
            var p = t.substring(start, colon)
            val dot = p.indexOf('.')
            if (dot >= 0) p = p.substring(0, dot) // fractions
            if (!allDigits(p) || p.length > 2) return null
            parts[count++] = p.toInt()
            start = colon + 1
            if (colon == t.length) break
        }
        if (count < 2) return null
        return if (parts[0] <= 24 && parts[1] <= 59 && parts[2] <= 60) parts else null
    }

    private fun toUnix(y: Long, mo: Int, d: Int, h: Int, mi: Int, s: Int, offsetMin: Int): Long {
        if (y < 1970 || y > 2200 || mo < 1 || mo > 12 || d < 1 || d > 31) return 0
        val t = daysFromCivil(y, mo, d) * 86400 + h * 3600L + mi * 60L + s - offsetMin.toLong() * 60
        return if (t > 0) t else 0
    }

    private fun parseIso8601(s: String): Long {
        // YYYY-MM-DD[(T| )HH:MM[:SS[.fff]]][Z|±HH[:MM]]
        if (s.length < 10 || !allDigits(s.substring(0, 4)) || s[4] != '-' || !allDigits(s.substring(5, 7)) || s[7] != '-' ||
            !allDigits(s.substring(8, 10))
        ) return 0
        val y = s.substring(0, 4).toInt()
        val mo = s.substring(5, 7).toInt()
        val d = s.substring(8, 10).toInt()
        var clock = intArrayOf(0, 0, 0)
        var offset = 0
        if (s.length > 10 && (s[10] == 'T' || s[10] == 't' || s[10] == ' ')) {
            val rest = s.substring(11)
            val zone = rest.indexOfFirst { it == 'Z' || it == 'z' || it == '+' || it == '-' }
            clock = parseClock(if (zone < 0) rest else rest.substring(0, zone)) ?: return 0
            if (zone >= 0) {
                val z = rest.substring(zone)
                if (z[0] == 'Z' || z[0] == 'z') offset = 0 else numericOffset(z)?.let { offset = it }
            }
        }
        return toUnix(y.toLong(), mo, d, clock[0], clock[1], clock[2], offset)
    }

    /**
     * "Wed, 02 Oct 2002 13:00:00 GMT", "+0200" / named zones, no weekday or seconds, 2-digit years, and ISO 8601 as
     * feeds also write it. Unix seconds; 0 = not a date.
     */
    fun parseRfc822(date: String): Long {
        val s = trimXml(date)
        if (s.isEmpty()) return 0
        val iso = parseIso8601(s)
        if (iso > 0) return iso
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (isXmlSpace(c) || c == ',') {
                if (cur.isNotEmpty()) tokens.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        var day = -1
        var month = -1
        var h = 0
        var mi = 0
        var sec = 0
        var offset = 0
        var year = -1L
        for (t in tokens) {
            if (t.indexOf(':') >= 0 && isAsciiDigit(t[0])) {
                // "13:00:00" (maybe with the zone glued on: "13:00:00+0200" / "13:00:00Z")
                var clock = t
                var zone = -1
                for (k in 1 until t.length) if (t[k] == '+' || t[k] == '-' || t[k] == 'Z' || t[k] == 'z') {
                    zone = k
                    break
                }
                if (zone >= 0) {
                    numericOffset(clock.substring(zone))?.let { offset = it }
                    clock = clock.substring(0, zone)
                }
                val c = parseClock(clock) ?: return 0
                h = c[0]
                mi = c[1]
                sec = c[2]
            } else if (allDigits(t)) {
                if (t.length <= 2 && day < 0) day = t.toInt()
                else if (t.length == 4 || (t.length == 2 && year < 0)) year = t.toLong()
            } else if (t[0] == '+' || t[0] == '-') {
                numericOffset(t)?.let { offset = it }
            } else if (monthIndex(t).let { it > 0 } && month < 0 && isAsciiAlpha(t[0])) {
                // Weekday names never start like a month ("Mon" / "Mar"...: "Mon" is no month), so the first match wins.
                month = monthIndex(t)
            } else {
                val z = zoneOffset(t)
                if (z != null) {
                    offset = z
                } else if (t.length > 3 && (t.substring(0, 3).equals("GMT", true) || t.substring(0, 3).equals("UTC", true))) {
                    numericOffset(t.substring(3))?.let { offset = it } // "GMT+0200"
                }
            }
        }
        if (day < 1 || month < 1 || year < 0) return 0
        if (year < 100) year += if (year < 70) 2000 else 1900
        return toUnix(year, month, day, h, mi, sec, offset)
    }

    private val LEADING_NUMBER = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /** itunes:duration: "3600", "3600.5", "59:02", "1:02:03". 0 = unknown. */
    fun parseDurationMs(text: String): Long {
        val s = trimXml(text)
        if (s.isEmpty()) return 0
        val parts = DoubleArray(3)
        var count = 0
        var start = 0
        while (true) {
            val colon = s.indexOf(':', start)
            val p = trimXml(if (colon < 0) s.substring(start) else s.substring(start, colon))
            if (p.isEmpty() || count == 3) return 0
            val m = LEADING_NUMBER.find(p) ?: return 0
            val v = m.value.toDoubleOrNull() ?: return 0
            if (v < 0) return 0
            // "45 min" / "3600s": a unit after the number is tolerated only on a plain number.
            if (m.value.length < p.length && (colon >= 0 || count > 0)) return 0
            parts[count++] = v
            if (colon < 0) break
            start = colon + 1
        }
        val seconds = when (count) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
        if (seconds <= 0 || seconds > 200.0 * 3600) return 0
        return (seconds * 1000 + 0.5).toLong()
    }

    fun isHttpUrl(u: String): Boolean {
        val l = asciiLower(u.take(8))
        return (l.startsWith("http://") && u.length > 7) || (l.startsWith("https://") && u.length > 8)
    }
}
