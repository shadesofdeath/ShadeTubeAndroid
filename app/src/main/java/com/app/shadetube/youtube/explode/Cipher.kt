package com.app.shadetube.youtube.explode

/** Single step of YouTube's signature transform plan (port of Bridge/Cipher/CipherManifest). */
data class CipherOperation(val kind: Kind, val index: Int = 0) {
    enum class Kind { Reverse, Splice, Swap }

    /**
     * reverse: a.reverse()
     * splice:  a.splice(0, index)            -> drops the first `index` characters
     * swap:    var c=a[0]; a[0]=a[index % a.length]; a[index % a.length]=c
     */
    fun apply(input: String): String = when (kind) {
        Kind.Reverse -> input.reversed()
        Kind.Splice -> when {
            index <= 0 -> input
            index >= input.length -> ""
            else -> input.substring(index)
        }
        Kind.Swap -> if (input.isEmpty()) input else {
            val target = Math.floorMod(index, input.length)
            val chars = input.toCharArray()
            val c = chars[0]
            chars[0] = chars[target]
            chars[target] = c
            String(chars)
        }
    }

    override fun toString() = when (kind) {
        Kind.Reverse -> "Reverse"
        Kind.Splice -> "Splice ($index)"
        Kind.Swap -> "Swap ($index)"
    }
}

/** Ordered list of operations that turns an encrypted signature ("s") into the real one. */
class CipherManifest(val signatureTimestamp: String, val operations: List<CipherOperation>) {
    fun decipher(input: String): String = operations.fold(input) { acc, op -> op.apply(acc) }
    override fun toString() = "sts=$signatureTimestamp [" + operations.joinToString(", ") + "]"
}
