package com.app.shadetube.library

import com.app.shadetube.catalog.Track
import com.app.shadetube.core.Text

/** Sort keys of a track list (C++ TrackTable::SortKey; [Artist] is an Android addition). */
enum class TrackSort { Custom, Title, Artist, Album, Added, Duration }

/** One entry of the sort menu ("Özel sıra", "Eklenme tarihi ↓", "Başlık", "Süre", "Albüm"). */
data class SortOption(val key: TrackSort, val descending: Boolean)

/**
 * The view logic of the C++ TrackTable (app/Components: rebuildView, headerClick, displayedTracks): the in-list search
 * filter and the sort. Pure; the displayed list is what gets queued on play.
 */
object TrackListView {
    /** Rows matching [filter] (folded: case / accents / Turkish I insensitive, over title + artists + album), sorted. */
    fun apply(tracks: List<Track>, filter: String, sort: TrackSort, descending: Boolean): List<Track> {
        val f = Text.fold(filter)
        val shown = if (f.isEmpty()) tracks else tracks.filter { Text.fold(it.name + " " + it.artistLine + " " + it.album.name).contains(f) }
        if (sort == TrackSort.Custom) return shown
        val sorted = when (sort) {
            TrackSort.Title -> shown.sortedBy { Text.fold(it.name) }
            TrackSort.Artist -> shown.sortedBy { Text.fold(it.artistLine) }
            TrackSort.Album -> shown.sortedBy { Text.fold(it.album.name) }
            TrackSort.Added -> shown.sortedBy { it.addedAt }
            TrackSort.Duration -> shown.sortedBy { it.durationMs }
            TrackSort.Custom -> shown
        } // stable, like std::stable_sort
        // The C++ reverses the whole ascending order (ties too).
        return if (descending) sorted.asReversed().toList() else sorted
    }

    /** Clicking a column header cycles: ascending -> descending -> custom order. */
    fun headerClick(current: TrackSort, descending: Boolean, clicked: TrackSort): SortOption = when {
        clicked == TrackSort.Custom -> SortOption(current, descending)
        current != clicked -> SortOption(clicked, false)
        !descending -> SortOption(clicked, true)
        else -> SortOption(TrackSort.Custom, false)
    }

    /**
     * The sort menu of a collection page: "Özel sıra", "Eklenme tarihi ↓" (not albums), "Başlık", "Sanatçı" (Android
     * addition), "Süre", "Albüm" (not albums).
     */
    fun sortOptions(kind: CollectionKind): List<SortOption> {
        val out = ArrayList<SortOption>()
        out.add(SortOption(TrackSort.Custom, false))
        if (kind != CollectionKind.Album) out.add(SortOption(TrackSort.Added, true))
        out.add(SortOption(TrackSort.Title, false))
        out.add(SortOption(TrackSort.Artist, false))
        out.add(SortOption(TrackSort.Duration, false))
        if (kind != CollectionKind.Album) out.add(SortOption(TrackSort.Album, false))
        return out
    }

    /** Header total ("· 3 sa 12 dk"): recommendations ("Geliştir") don't count. */
    fun totalMs(tracks: List<Track>): Long = tracks.filter { !it.recommended }.sumOf { it.durationMs.toLong() }
}
