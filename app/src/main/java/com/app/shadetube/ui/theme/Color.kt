package com.app.shadetube.ui.theme

import androidx.compose.ui.graphics.Color

/** Neutral dark palette (tokens.json → color.dark). The UI is a neutral stage; the only hue is the accent. */
object ShadeDark {
    val surface0 = Color(0xFF0B0C10)
    val surface1 = Color(0xFF12141A)
    val surface2 = Color(0xFF1A1D25)
    val surface3 = Color(0xFF232732)
    val outline = Color(0xFF2B303C)
    val outlineVariant = Color(0xFF1F232D)
    val text1 = Color(0xFFF3F1EA)
    val text2 = Color(0xFFA6AAB6)
    val text3 = Color(0xFF6E7382)
    val scrim = Color(0xB3000000)
    val error = Color(0xFFFF6B6B)
    val onError = Color(0xFF1A0B0B)
    val warning = Color(0xFFF0B35A)
    val success = Color(0xFF6FCF97)
    val accentFallback = Color(0xFFD8D6CF)
}

/** Neutral light palette (tokens.json → color.light). */
object ShadeLight {
    val surface0 = Color(0xFFF7F6F1)
    val surface1 = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFEEEDE7)
    val surface3 = Color(0xFFE3E1DA)
    val outline = Color(0xFFD3D1C9)
    val outlineVariant = Color(0xFFE6E4DD)
    val text1 = Color(0xFF15171D)
    val text2 = Color(0xFF5B606D)
    val text3 = Color(0xFF8A8F9C)
    val scrim = Color(0x66000000)
    val error = Color(0xFFB3261E)
    val onError = Color(0xFFFFFFFF)
    val warning = Color(0xFF8A5A00)
    val success = Color(0xFF2E7D4F)
    val accentFallback = Color(0xFF4A4F5C)
}

/** Text on an accent fill: ink when the accent is light (OKLCH L > 0.60), else white (tokens: onAccentDark / Light). */
val OnAccentInk = Color(0xFF0E0F13)
val OnAccentWhite = Color(0xFFFFFFFF)

/** Brand amber of the crescent mark (logo, launcher icon, splash) and the default Fixed accent preset. */
val BrandAmber = Color(0xFFF0B35A)

/** The 12 Fixed-accent presets of Settings › Appearance (tokens.json → color.accentPresets), in display order. */
object AccentPresets {
    val kehribar = Color(0xFFF0B35A)
    val turkuaz = Color(0xFF5FD4C4)
    val gul = Color(0xFFF08CA8)
    val lavanta = Color(0xFF9B8CE6)
    val gokyuzu = Color(0xFF5B8DEF)
    val nane = Color(0xFF58B37A)
    val mandalina = Color(0xFFE07A3A)
    val mercan = Color(0xFFFF7F6E)
    val limon = Color(0xFFE3D15A)
    val buz = Color(0xFF9FD8F0)
    val kum = Color(0xFFD8C3A5)
    val gri = Color(0xFFB8BCC8)

    /** (key, color); the key is the stable id used in tokens.json. */
    val all: List<Pair<String, Color>> = listOf(
        "kehribar" to kehribar, "turkuaz" to turkuaz, "gul" to gul, "lavanta" to lavanta,
        "gokyuzu" to gokyuzu, "nane" to nane, "mandalina" to mandalina, "mercan" to mercan,
        "limon" to limon, "buz" to buz, "kum" to kum, "gri" to gri,
    )
}

/** Parses "#RRGGBB" / "#AARRGGBB" / "RRGGBB" (Settings.fixedAccent); null when malformed. */
fun parseHexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** "#RRGGBB" (upper case) — the format Settings.fixedAccent stores. */
fun Color.toHex(): String {
    val argb = (alpha * 255 + .5f).toInt() shl 24 or ((red * 255 + .5f).toInt() shl 16) or
        ((green * 255 + .5f).toInt() shl 8) or (blue * 255 + .5f).toInt()
    return "#%06X".format(argb and 0xFFFFFF)
}
