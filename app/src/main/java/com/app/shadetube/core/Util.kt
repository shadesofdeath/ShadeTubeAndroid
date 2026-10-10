package com.app.shadetube.core

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Small shared helpers (hashes, time, text folding). */
object Hash {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha256Hex(text: String): String = sha256(text.toByteArray()).toHex()
    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
    fun md5Hex(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).toHex()

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").run { init(SecretKeySpec(key, "HmacSHA1")); doFinal(data) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** 64-bit FNV-1a over the UTF-8 bytes (local file ids: "local:<hex>"). */
    fun fnv1a64(text: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    fun fnv1a64Hex(text: String): String = java.lang.Long.toUnsignedString(fnv1a64(text), 16).padStart(16, '0')
}

fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v shr 4]).append(hex[v and 15])
    }
    return sb.toString()
}

object Time {
    fun nowMs(): Long = System.currentTimeMillis()
    fun nowUnix(): Long = System.currentTimeMillis() / 1000
    /** Monotonic milliseconds (C++ steadyMs): for deadlines and throttles, never for stored timestamps. */
    fun steadyMs(): Long = android.os.SystemClock.elapsedRealtime()
}

object Text {
    private val marks = Regex("\\p{Mn}+")

    /**
     * Case / accent / Turkish-I insensitive form for matching and search (C++ shortcuts::foldForSearch):
     * "İstanbul" / "ıstanbul" / "ISTANBUL" all fold to "istanbul".
     */
    fun fold(s: String): String {
        val lowered = buildString(s.length) {
            for (c in s) append(
                when (c) {
                    'İ', 'I', 'ı' -> 'i'
                    'ß' -> 's'
                    else -> c
                },
            )
        }.lowercase(Locale.ROOT)
        return marks.replace(Normalizer.normalize(lowered, Normalizer.Form.NFD), "")
    }

    /** Lower-cased, punctuation stripped, whitespace collapsed (title / artist matching). */
    fun normalizeForMatch(s: String): String =
        fold(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
