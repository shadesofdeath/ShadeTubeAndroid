package com.app.shadetube.stats

/**
 * std::mt19937_64 (the C++ smart lists / smart mix seed their orders with it): the same seed gives the same raw
 * sequence as the C++ engine. The distributions on top ([nextUnit], [nextBelow]) are simple equivalents, not
 * bit-identical to a particular standard library's.
 */
class Mt19937(seed: Long) {
    private val mt = LongArray(NN)
    private var mti = NN

    init {
        mt[0] = seed
        for (i in 1 until NN) mt[i] = 6364136223846793005L * (mt[i - 1] xor (mt[i - 1] ushr 62)) + i
    }

    fun next(): Long {
        if (mti >= NN) {
            for (i in 0 until NN) {
                val x = (mt[i] and UM) or (mt[(i + 1) % NN] and LM)
                var xA = x ushr 1
                if (x and 1L != 0L) xA = xA xor MATRIX_A
                mt[i] = mt[(i + MM) % NN] xor xA
            }
            mti = 0
        }
        var x = mt[mti++]
        x = x xor ((x ushr 29) and 0x5555555555555555L)
        x = x xor ((x shl 17) and 0x71D67FFFEDA60000L)
        x = x xor ((x shl 37) and -0x8112000000000L) // 0xFFF7EEE000000000
        x = x xor (x ushr 43)
        return x
    }

    /** Uniform in (Double.MIN_VALUE, 1) (std::uniform_real_distribution(DBL_MIN, 1.0)). */
    fun nextUnit(): Double {
        val r = (next() ushr 11).toDouble() * (1.0 / (1L shl 53))
        return if (r <= 0.0) java.lang.Double.MIN_NORMAL else r
    }

    /** Uniform in 0 until n (n > 0). */
    fun nextBelow(n: Long): Long = java.lang.Long.remainderUnsigned(next(), n)

    private companion object {
        const val NN = 312
        const val MM = 156
        const val MATRIX_A = -0x4afd90a55699e617L // 0xB5026F5AA96619E9
        const val UM = -0x80000000L // 0xFFFFFFFF80000000: most significant 33 bits
        const val LM = 0x7FFFFFFFL // least significant 31 bits
    }
}

/** FNV-1a 64 over the UTF-8 bytes (smart lists / smart mix seeds). */
internal fun fnv64(s: String, basis: Long = -0x340d631b7bdddcdbL): Long {
    var h = basis
    for (b in s.toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toLong() and 0xFF)
        h *= 1099511628211L
    }
    return h
}
