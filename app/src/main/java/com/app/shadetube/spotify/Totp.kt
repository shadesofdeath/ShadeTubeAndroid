package com.app.shadetube.spotify

import com.app.shadetube.core.Hash

/**
 * RFC 6238 TOTP used to sign Spotify's web-player token request (the "totp" / "totpServer" params).
 * The secret is a Base32 string ("nuance") fetched at runtime; see [Auth]. HMAC-SHA1 via [Hash.hmacSha1]
 * (the C++ used Windows CNG).
 */
object Totp {
    /** Base32 (RFC 4648) decode; ignores padding, whitespace and case. Non-alphabet chars are skipped. */
    fun base32Decode(s: String): ByteArray {
        fun value(c: Char): Int = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a'
            in '2'..'7' -> c - '2' + 26
            else -> -1
        }
        val out = java.io.ByteArrayOutputStream(s.length * 5 / 8 + 1)
        var buffer = 0L
        var bits = 0
        for (c in s) {
            val v = value(c)
            if (v < 0) continue // skip '=', whitespace, anything not in the alphabet
            buffer = ((buffer shl 5) or v.toLong()) and 0xFFFFFFFFL
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write(((buffer shr bits) and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    /**
     * TOTP code for the given UNIX time (seconds). Spotify uses digits = 6, period = 30, SHA1.
     * [unixSeconds] must be Spotify's server time (see [Auth.fetchServerTimeSeconds]), not the local clock.
     */
    fun totp(base32Secret: String, unixSeconds: Long, digits: Int = 6, period: Int = 30): String {
        val key = base32Decode(base32Secret)
        var counter = unixSeconds / period
        val ctr = ByteArray(8)
        for (i in 7 downTo 0) {
            ctr[i] = (counter and 0xFF).toByte()
            counter = counter ushr 8
        }
        val mac = Hash.hmacSha1(key, ctr)
        val offset = mac.last().toInt() and 0x0F
        val binary = ((mac[offset].toLong() and 0x7F) shl 24) or
            ((mac[offset + 1].toLong() and 0xFF) shl 16) or
            ((mac[offset + 2].toLong() and 0xFF) shl 8) or
            (mac[offset + 3].toLong() and 0xFF)
        var divisor = 1L
        repeat(digits) { divisor *= 10 }
        // The C++ divisor is a uint32 (10^digits wraps above 9 digits); Spotify only ever uses 6.
        divisor = divisor and 0xFFFFFFFFL
        val code = binary % divisor
        return code.toString().padStart(digits, '0')
    }
}
