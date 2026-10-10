package com.app.shadetube.app

import android.content.res.Resources
import com.app.shadetube.R
import com.app.shadetube.internetradio.RadioGenres
import com.app.shadetube.internetradio.RadioLabels
import java.util.Locale

/** [RadioLabels] from strings.xml (country names in the UI language, translated genre chips). */
class ResourceRadioLabels(private val res: Resources) : RadioLabels {
    private val locale: Locale = res.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()

    override fun countryName(code: String, fallback: String): String {
        if (code.length == 2) {
            val name = runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull().orEmpty()
            if (name.isNotEmpty() && !name.equals(code, ignoreCase = true)) return name
        }
        return fallback
    }

    override fun knownGenreLabel(tag: String): String? {
        val key = RadioGenres.key(tag) ?: return null
        return GENRES[key]?.let { res.getString(it) } ?: RadioGenres.englishLabel(tag)
    }

    override val internetRadio: String = res.getString(R.string.radio_internet_radio)

    companion object {
        /** RadioGenres.key -> its string. */
        private val GENRES: Map<String, Int> = mapOf(
            "pop" to R.string.radio_genre_pop, "rock" to R.string.radio_genre_rock, "news" to R.string.radio_genre_news,
            "jazz" to R.string.radio_genre_jazz, "classical" to R.string.radio_genre_classical, "lofi" to R.string.radio_genre_lofi,
            "dance" to R.string.radio_genre_dance, "electronic" to R.string.radio_genre_electronic, "80s" to R.string.radio_genre_80s,
            "90s" to R.string.radio_genre_90s, "70s" to R.string.radio_genre_70s, "60s" to R.string.radio_genre_60s,
            "hiphop" to R.string.radio_genre_hiphop, "chillout" to R.string.radio_genre_chillout,
            "ambient" to R.string.radio_genre_ambient, "oldies" to R.string.radio_genre_oldies, "talk" to R.string.radio_genre_talk,
            "metal" to R.string.radio_genre_metal, "blues" to R.string.radio_genre_blues, "soul" to R.string.radio_genre_soul,
            "reggae" to R.string.radio_genre_reggae, "country" to R.string.radio_genre_country, "sports" to R.string.radio_genre_sports,
            "house" to R.string.radio_genre_house, "top 40" to R.string.radio_genre_top40, "hits" to R.string.radio_genre_hits,
            "classic rock" to R.string.radio_genre_classic_rock, "alternative" to R.string.radio_genre_alternative,
            "indie" to R.string.radio_genre_indie, "folk" to R.string.radio_genre_folk, "turkish" to R.string.radio_genre_turkish,
            "arabesk" to R.string.radio_genre_arabesk, "religious" to R.string.radio_genre_religious,
            "christian" to R.string.radio_genre_christian, "kids" to R.string.radio_genre_kids, "latin" to R.string.radio_genre_latin,
            "techno" to R.string.radio_genre_techno, "trance" to R.string.radio_genre_trance, "rnb" to R.string.radio_genre_rnb,
            "funk" to R.string.radio_genre_funk, "disco" to R.string.radio_genre_disco,
            "world music" to R.string.radio_genre_world_music, "public radio" to R.string.radio_genre_public_radio,
            "community radio" to R.string.radio_genre_community_radio, "instrumental" to R.string.radio_genre_instrumental,
            "lounge" to R.string.radio_genre_lounge, "soundtrack" to R.string.radio_genre_soundtrack, "rap" to R.string.radio_genre_rap,
            "punk" to R.string.radio_genre_punk,
        )
    }
}
