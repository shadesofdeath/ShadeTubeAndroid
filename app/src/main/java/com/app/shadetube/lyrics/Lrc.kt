package com.app.shadetube.lyrics

import java.util.Locale

/** LRC parsing and writing (port of the LRC part of lyrics/Lyrics.cpp). Pure. */
object Lrc {
    private const val SPACE = " \t\r\n\u000B\u000C"

    internal fun trim(s: String): String {
        var b = 0
        var e = s.length
        while (b < e && SPACE.indexOf(s[b]) >= 0) b++
        while (e > b && SPACE.indexOf(s[e - 1]) >= 0) e--
        return s.substring(b, e)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    /** std::atoi: leading whitespace, optional sign, digits; 0 when there are none. */
    internal fun atoi(s: String): Int {
        var i = 0
        while (i < s.length && SPACE.indexOf(s[i]) >= 0) i++
        var neg = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) neg = s[i++] == '-'
        var v = 0L
        while (i < s.length && isDigit(s[i])) {
            v = (v * 10 + (s[i] - '0')).coerceAtMost(Int.MAX_VALUE.toLong() + 1)
            i++
        }
        if (neg) v = -v
        return v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** Parses "mm:ss", "mm:ss.x", "mm:ss.xx", "mm:ss.xxx" (also "mm:ss:xx") -> ms. -1 if not a timestamp. */
    internal fun parseTimestamp(t: String): Int {
        var i = 0
        var minutes = 0
        var seconds = 0
        var frac = 0
        var digits = 0
        while (i < t.length && isDigit(t[i]) && digits < 4) {
            minutes = minutes * 10 + (t[i++] - '0'); digits++
        }
        if (digits == 0 || i >= t.length || t[i] != ':') return -1
        i++
        digits = 0
        while (i < t.length && isDigit(t[i]) && digits < 2) {
            seconds = seconds * 10 + (t[i++] - '0'); digits++
        }
        if (digits == 0 || seconds > 59) return -1
        if (i < t.length && (t[i] == '.' || t[i] == ':')) {
            i++
            digits = 0
            while (i < t.length && isDigit(t[i])) {
                if (digits < 3) {
                    frac = frac * 10 + (t[i] - '0'); digits++
                }
                i++
            }
            if (digits == 0) return -1
            if (digits == 1) frac *= 100 else if (digits == 2) frac *= 10
        }
        if (i != t.length) return -1
        return (minutes * 60 + seconds) * 1000 + frac
    }

    internal fun formatTimestamp(msIn: Int): String {
        val ms = maxOf(0, msIn)
        return String.format(Locale.ROOT, "%02d:%02d.%02d", ms / 60000, ms / 1000 % 60, ms % 1000 / 10)
    }

    /**
     * The text of a timed line and its enhanced-LRC words: "<mm:ss.xx>word <mm:ss.xx>word". A piece before the first
     * word timestamp starts at the line's own time. Without word timestamps the words stay empty. The words
     * concatenate to the returned text (outer whitespace trimmed).
     */
    private fun parseWords(s: String, lineMs: Int): Pair<String, List<LyricWord>> {
        class Piece(val timeMs: Int, var text: String = "")
        val pieces = mutableListOf(Piece(lineMs))
        val cur = StringBuilder()
        var timed = false
        var i = 0
        while (i < s.length) {
            if (s[i] == '<') {
                val close = s.indexOf('>', i + 1)
                if (close >= 0) {
                    val t = parseTimestamp(s.substring(i + 1, close))
                    if (t >= 0) {
                        timed = true
                        pieces.last().text = cur.toString()
                        cur.setLength(0)
                        pieces += Piece(t)
                        i = close + 1
                        continue
                    }
                }
            }
            cur.append(s[i])
            i++
        }
        pieces.last().text = cur.toString()
        // Outer whitespace belongs to no word; pieces left empty (a closing "<mm:ss.xx>" end mark) are dropped.
        while (pieces.isNotEmpty() && trim(pieces.first().text).isEmpty()) pieces.removeAt(0)
        while (pieces.isNotEmpty() && trim(pieces.last().text).isEmpty()) pieces.removeAt(pieces.size - 1)
        if (pieces.isNotEmpty()) {
            pieces.first().text = pieces.first().text.trimStart(' ', '\t')
            pieces.last().text = pieces.last().text.trimEnd(' ', '\t', '\r')
        }
        pieces.removeAll { it.text.isEmpty() }
        val text = pieces.joinToString("") { it.text }
        val words = if (timed && pieces.size > 1) pieces.map { LyricWord(it.timeMs, it.text) } else emptyList()
        return text to words
    }

    internal fun splitLines(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            if (s[i] == '\n' || s[i] == '\r') {
                out += s.substring(start, i)
                if (s[i] == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    /** Unsynced lyrics: trimmed lines, leading / trailing blank lines dropped (inner blanks stay as stanza breaks). */
    internal fun plainToLyrics(textIn: String): Lyrics {
        val text = textIn.removePrefix("\uFEFF")
        val lines = splitLines(text).map { LyricLine(-1, trim(it)) }.toMutableList()
        while (lines.isNotEmpty() && lines.last().text.isEmpty()) lines.removeAt(lines.size - 1)
        val first = lines.indexOfFirst { it.text.isNotEmpty() }
        return Lyrics(lines = if (first < 0) emptyList() else lines.subList(first, lines.size).toList())
    }

    /** Parses "[mm:ss.xx] text" LRC content (several timestamps per line, [offset:], enhanced-LRC word timestamps). */
    fun parse(lrcIn: String): Lyrics {
        val lrc = lrcIn.removePrefix("\uFEFF")
        val lines = ArrayList<LyricLine>()
        val untimed = ArrayList<String>() // text lines without timestamps (plain fallback)
        var offsetMs = 0
        for (raw in splitLines(lrc)) {
            var line = trim(raw)
            val times = ArrayList<Int>()
            var metadata = false
            while (line.isNotEmpty() && line[0] == '[') {
                val close = line.indexOf(']')
                if (close < 0) break
                val tag = line.substring(1, close)
                val t = parseTimestamp(trim(tag))
                if (t >= 0) {
                    times += t
                } else if (times.isEmpty()) {
                    // [ar:..] [ti:..] [al:..] [by:..] [length:..] [offset:+/-ms] ...
                    val colon = tag.indexOf(':')
                    if (colon < 0) break // "[Chorus]" etc. -> treat as text
                    val key = trim(tag.substring(0, colon)).lowercase(Locale.ROOT)
                    if (key == "offset") offsetMs = atoi(trim(tag.substring(colon + 1)))
                    metadata = true
                } else {
                    break // non-time tag after timestamps: part of the text
                }
                line = line.substring(close + 1)
            }
            if (times.isNotEmpty()) {
                val (text, words) = parseWords(line, times.first())
                // Word times are absolute: a line repeated at several timestamps keeps them only for its first one.
                times.forEachIndexed { i, t -> lines += LyricLine(t, text, if (i == 0) words else emptyList()) }
            } else if (!metadata) {
                untimed += line
            }
        }

        if (lines.isEmpty()) {
            // No timestamps at all: unsynced lyrics.
            return plainToLyrics(untimed.joinToString("") { it + "\n" })
        }
        // LRC semantics: a positive offset makes lyrics appear sooner.
        val shifted = lines.map { l ->
            l.copy(
                timeMs = maxOf(0, l.timeMs - offsetMs),
                words = l.words.map { it.copy(timeMs = maxOf(0, it.timeMs - offsetMs)) },
            )
        }.sortedBy { it.timeMs }
        return Lyrics(synced = true, lines = shifted)
    }

    /**
     * Lyrics as LRC text ("[mm:ss.xx]text" per line, word timestamps kept), optionally with [ti:] / [ar:] / [al:]
     * tags. Unsynced lyrics give plain lines.
     */
    fun toLrc(l: Lyrics, title: String = "", artist: String = "", album: String = ""): String {
        val out = StringBuilder()
        fun tag(key: String, v: String) {
            if (v.isEmpty()) return
            out.append('[').append(key).append(':').append(v.replace('\n', ' ').replace(']', ')')).append("]\n")
        }
        tag("ti", title)
        tag("ar", artist)
        tag("al", album)
        if (out.isNotEmpty()) out.append("[by:ShadeTube]\n")
        for (line in l.lines) {
            if (!l.synced || line.timeMs < 0) {
                out.append(line.text).append('\n')
                continue
            }
            out.append('[').append(formatTimestamp(line.timeMs)).append(']')
            if (line.words.isEmpty()) out.append(line.text)
            else for (w in line.words) out.append('<').append(formatTimestamp(w.timeMs)).append('>').append(w.text)
            out.append('\n')
        }
        return out.toString()
    }

    /** Plain text, one line per line (instrumental gaps as empty lines). */
    fun toPlainText(l: Lyrics): String = l.lines.joinToString("\n") { it.text }
}
