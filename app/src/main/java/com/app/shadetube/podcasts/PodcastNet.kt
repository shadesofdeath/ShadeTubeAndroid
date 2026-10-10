package com.app.shadetube.podcasts

/** Network helpers of the podcasts package (executeCancellable / PublicOnlyInterceptor / readCapped live in core/Net.kt). */

/** "audio/mpeg; charset=x" -> "audio/mpeg" (lower case). */
internal fun mediaType(raw: String?): String {
    var t = asciiLower(raw.orEmpty())
    val semi = t.indexOf(';')
    if (semi >= 0) t = t.substring(0, semi)
    return trimXml(t)
}
