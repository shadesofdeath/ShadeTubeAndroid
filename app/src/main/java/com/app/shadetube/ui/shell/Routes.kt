package com.app.shadetube.ui.shell

import android.net.Uri
import com.app.shadetube.catalog.LIKED_SONGS_URI
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.library.Destination
import com.app.shadetube.ui.nav.BroadcastTab
import com.app.shadetube.ui.nav.RadioListKind
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.SettingsSection
import com.app.shadetube.ui.nav.Tab

/**
 * Navigation-compose routes. Every tab is a nested graph (`tab_<Tab>`) with a root (`<Tab>/root`) and one generic detail
 * destination (`<Tab>/s/{code}`) whose argument is the [Screen] encoded by [ScreenCodec] — so a detail screen pushes onto
 * the tab it was opened from, and each tab keeps its own back stack (saveState / restoreState).
 */
internal object Routes {
    const val ARG = "code"

    fun graph(tab: Tab) = "tab_${tab.name}"
    fun root(tab: Tab) = "${tab.name}/root"
    fun detailPattern(tab: Tab) = "${tab.name}/s/{$ARG}"
    fun detail(tab: Tab, screen: Screen) = "${tab.name}/s/${Uri.encode(ScreenCodec.encode(screen))}"

    /** The decoded detail argument is an import screen. */
    fun isImport(code: String?): Boolean = code != null && ScreenCodec.decode(code) is Screen.Import

    /** The tab a route belongs to ("Home/root", "Search/s/..."); null for graph routes. */
    fun tabOf(route: String?): Tab? {
        if (route == null) return null
        val head = route.substringBefore('/')
        if (head == route) return null
        return Tab.entries.firstOrNull { it.name == head }
    }
}

/** [Screen] <-> one string: fields joined with '|', each field URI-encoded (so '|' never appears inside one). */
internal object ScreenCodec {
    private fun join(vararg parts: Any) = parts.joinToString("|") { Uri.encode(it.toString()) }

    fun encode(s: Screen): String = when (s) {
        Screen.Home -> join("Home")
        Screen.Search -> join("Search")
        is Screen.Library -> join("Library", s.folderId)
        is Screen.Broadcast -> join("Broadcast", s.tab.name)
        is Screen.Collection -> join("Collection", s.kind.name, s.id, s.autoplay)
        is Screen.SmartList -> join("SmartList", s.id)
        is Screen.Artist -> join("Artist", s.id)
        is Screen.HomeSection -> join("HomeSection", s.key)
        is Screen.RadioList -> join("RadioList", s.kind.name, s.value, s.title)
        is Screen.PodcastShow -> join("PodcastShow", s.feedOrAppleId)
        is Screen.PodcastEpisode -> join("PodcastEpisode", s.episodeId)
        Screen.Profile -> join("Profile")
        Screen.Stats -> join("Stats")
        is Screen.YearInReview -> join("YearInReview", s.year)
        is Screen.Settings -> join("Settings", s.section.name)
        Screen.Equalizer -> join("Equalizer")
        Screen.Blocklist -> join("Blocklist")
        Screen.Downloads -> join("Downloads")
        Screen.LocalFiles -> join("LocalFiles")
        Screen.Welcome -> join("Welcome")
        is Screen.Import -> join("Import", s.pendingFromIntent, s.youtubeUrl)
    }

    fun decode(code: String): Screen? = runCatching {
        val p = code.split('|').map { Uri.decode(it) }
        fun a(i: Int) = p.getOrElse(i) { "" }
        when (p[0]) {
            "Home" -> Screen.Home
            "Search" -> Screen.Search
            "Library" -> Screen.Library(a(1))
            "Broadcast" -> Screen.Broadcast(BroadcastTab.valueOf(a(1)))
            "Collection" -> Screen.Collection(CollectionKind.valueOf(a(1)), a(2), a(3).toBoolean())
            "SmartList" -> Screen.SmartList(a(1))
            "Artist" -> Screen.Artist(a(1))
            "HomeSection" -> Screen.HomeSection(a(1))
            "RadioList" -> Screen.RadioList(RadioListKind.valueOf(a(1)), a(2), a(3))
            "PodcastShow" -> Screen.PodcastShow(a(1))
            "PodcastEpisode" -> Screen.PodcastEpisode(a(1))
            "Profile" -> Screen.Profile
            "Stats" -> Screen.Stats
            "YearInReview" -> Screen.YearInReview(a(1).toInt())
            "Settings" -> Screen.Settings(SettingsSection.valueOf(a(1)))
            "Equalizer" -> Screen.Equalizer
            "Blocklist" -> Screen.Blocklist
            "Downloads" -> Screen.Downloads
            "LocalFiles" -> Screen.LocalFiles
            "Welcome" -> Screen.Welcome
            "Import" -> Screen.Import(a(1).toBoolean(), a(2))
            else -> null
        }
    }.getOrNull()
}

/** Library-layer destinations (cards, rows) as screens. */
internal fun Destination.toScreen(autoplay: Boolean): Screen = when (this) {
    Destination.Liked -> Screen.Collection(CollectionKind.Liked, LIKED_SONGS_URI, autoplay)
    is Destination.Playlist ->
        if (id == LIKED_SONGS_URI) Screen.Collection(CollectionKind.Liked, LIKED_SONGS_URI, autoplay)
        else Screen.Collection(CollectionKind.Playlist, id, autoplay)
    is Destination.Album -> Screen.Collection(CollectionKind.Album, id, autoplay)
    is Destination.Artist -> Screen.Artist(id)
    is Destination.Library -> Screen.Library(folderId)
    is Destination.SmartList -> Screen.SmartList(id)
}
