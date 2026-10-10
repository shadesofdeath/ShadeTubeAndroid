package com.app.shadetube.spotify

import com.app.shadetube.core.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports tests/spotify testBase32 / testTotp (offline parts) plus the token request shape. */
class TotpTest {
    private fun hex(s: String) = Totp.base32Decode(s).toHex()

    /** RFC 4648 §10 base32 test vectors. */
    @Test
    fun base32Vectors() {
        assertEquals("", hex(""))
        assertEquals("66", hex("MY======")) // "f"
        assertEquals("666f", hex("MZXQ====")) // "fo"
        assertEquals("666f6f", hex("MZXW6===")) // "foo"
        assertEquals("666f6f62", hex("MZXW6YQ=")) // "foob"
        assertEquals("666f6f6261", hex("MZXW6YTB")) // "fooba"
        assertEquals("666f6f626172", hex("MZXW6YTBOI======")) // "foobar"
        // lowercase + stray whitespace must be tolerated (secrets sometimes arrive formatted).
        assertEquals("666f6f6261", hex("mz xw 6y tb"))
        // The RFC 6238 seed "12345678901234567890" as base32.
        assertEquals(20, Totp.base32Decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").size)
    }

    /** RFC 6238 Appendix B reference table (SHA-1, digits = 8) and its 6-digit tails. */
    @Test
    fun rfc6238Vectors() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // ASCII "12345678901234567890"
        val vectors = listOf(
            Triple(59L, "94287082", "287082"),
            Triple(1111111109L, "07081804", "081804"),
            Triple(1111111111L, "14050471", "050471"),
            Triple(1234567890L, "89005924", "005924"),
            Triple(2000000000L, "69279037", "279037"),
            Triple(20000000000L, "65353130", "353130"),
        )
        for ((t, eight, six) in vectors) {
            assertEquals("t=$t", six, Totp.totp(secret, t))
            assertEquals("t=$t", eight, Totp.totp(secret, t, 8))
        }
        // Codes must be stable within a 30 s window and change across it (base aligned to a period boundary).
        assertEquals(Totp.totp(secret, 1200), Totp.totp(secret, 1200 + 29))
        assertNotEquals(Totp.totp(secret, 1200), Totp.totp(secret, 1200 + 30))
    }

    @Test
    fun fallbackNuance() {
        assertTrue(Auth.FALLBACK_NUANCE_VERSION > 0)
        assertTrue(Totp.base32Decode(Auth.FALLBACK_NUANCE_SECRET).isNotEmpty())
        val code = Totp.totp(Auth.FALLBACK_NUANCE_SECRET, 1700000000)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun nuanceGistPicksHighestVersion() {
        assertEquals(Nuance(62, "BBBB"), Auth.pickNuance("""[{"v":59,"s":"AAAA"},{"v":62,"s":"BBBB"},{"v":63,"s":""},{"v":60,"s":"CCCC"}]"""))
        assertNull(Auth.pickNuance("""{"v":1,"s":"A"}"""))
        assertNull(Auth.pickNuance("not json"))
        assertNull(Auth.pickNuance("[]"))
    }

    @Test
    fun tokenRequestShape() {
        assertEquals(
            "https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=123456&totpServer=123456&totpVer=61",
            Auth.tokenUrl("123456", 61),
        )
        val t = TokenBundle("x", expiresAtMs = 1_000_000)
        assertTrue(!t.expired(939_999))
        assertTrue(t.expired(940_000)) // a minute early
        assertTrue(!TokenBundle("x", 0).expired(Long.MAX_VALUE)) // unknown expiry never "expires"
    }
}
