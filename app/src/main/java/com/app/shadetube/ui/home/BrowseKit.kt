package com.app.shadetube.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.library.AlbumType
import com.app.shadetube.library.Card
import com.app.shadetube.library.CardPlaceholder
import com.app.shadetube.library.CardShape
import com.app.shadetube.library.CardSubtitle
import com.app.shadetube.library.CompactCount
import com.app.shadetube.library.Destination
import com.app.shadetube.library.TotalDuration
import com.app.shadetube.stats.SmartList
import com.app.shadetube.stats.SmartLists
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.urlFor
import com.app.shadetube.ui.theme.AccentPresets
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.clampAccent
import java.time.format.TextStyle
import java.util.Locale

/*
 * Browse building blocks shared by the Browse screens (home, search, library, collection, artist). Candidates for
 * promotion to ui/components: BrowseTile (CoverTile with Liked / folder covers), LikedCover, FolderCover, CardStrip.
 */

// ------------------------------------------------------------------ text of library-layer data

/** The UI language as a java Locale (for upper-casing overlines and month / day names). */
@Composable
@ReadOnlyComposable
fun uiLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** A card's title: "Beğenilen Şarkılar" for Liked Songs, "Adsız klasör" for an untitled folder. */
@Composable
@ReadOnlyComposable
fun cardTitle(card: Card): String = when {
    card.destination == Destination.Liked -> stringResource(R.string.library_liked_songs)
    card.isFolder && card.title.isEmpty() -> stringResource(R.string.home_untitled_folder)
    else -> card.title
}

@Composable
@ReadOnlyComposable
fun albumTypeText(type: AlbumType): String = when (type) {
    AlbumType.Single -> stringResource(R.string.home_album_type_single)
    AlbumType.EP -> stringResource(R.string.home_album_type_ep)
    AlbumType.Album -> stringResource(R.string.home_album_type_album)
}

/** [CardSubtitle] as shown (null = no subtitle line). */
@Composable
@ReadOnlyComposable
fun cardSubtitle(s: CardSubtitle): String? = when (s) {
    CardSubtitle.None -> null
    is CardSubtitle.Text -> s.text.ifEmpty { null }
    is CardSubtitle.Songs -> pluralStringResource(R.plurals.home_songs_count, s.count, s.count)
    is CardSubtitle.Playlists -> pluralStringResource(R.plurals.home_playlists_count, s.count, s.count)
    CardSubtitle.PlaylistWord -> stringResource(R.string.label_playlist)
    is CardSubtitle.ArtistKind -> {
        val kind = stringResource(if (s.group) R.string.home_artist_kind_group else R.string.label_artist)
        if (s.country.isEmpty()) kind else "$kind · ${s.country}"
    }
    CardSubtitle.SimilarArtist -> stringResource(R.string.home_similar_artist)
    is CardSubtitle.ArtistYear -> listOf(s.artist, s.year).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    is CardSubtitle.AlbumLine -> listOf(s.year, albumTypeText(s.type), s.artist).filter { it.isNotEmpty() }.joinToString(" · ")
    is CardSubtitle.Year -> s.year.ifEmpty { null }
}

/** "2 sa 51 dk" / "45 dk". */
@Composable
@ReadOnlyComposable
fun totalDurationText(d: TotalDuration): String =
    if (d.hasHours) stringResource(R.string.home_duration_hm, d.hours.toInt(), d.minutes.toInt())
    else stringResource(R.string.home_duration_m, d.minutes.toInt())

/** "12,4 B" / "1,2 Mn" / "830". */
@Composable
@ReadOnlyComposable
fun compactCountText(c: CompactCount): String = when (c.scale) {
    CompactCount.Scale.None -> c.number
    CompactCount.Scale.Thousand -> stringResource(R.string.home_compact_thousand, c.number)
    CompactCount.Scale.Million -> stringResource(R.string.home_compact_million, c.number)
}

// ------------------------------------------------------------------ smart lists ("Senin için yapıldı")

@Composable
@ReadOnlyComposable
fun smartListTitle(list: SmartList): String = when (list.kind) {
    SmartLists.Kind.Month -> stringResource(R.string.home_smart_month)
    SmartLists.Kind.Best -> stringResource(R.string.home_smart_best)
    SmartLists.Kind.Rediscover -> stringResource(R.string.home_smart_rediscover)
    SmartLists.Kind.Discoveries -> stringResource(R.string.home_smart_discoveries)
    SmartLists.Kind.Forgotten -> stringResource(R.string.home_smart_forgotten)
    SmartLists.Kind.Mix -> stringResource(R.string.home_smart_mix, list.number)
    SmartLists.Kind.Year -> stringResource(R.string.home_smart_year, list.number)
}

/** The card's mono overline: the month ("EKİM"), the mix's artists, the year, else the song count. */
@Composable
@ReadOnlyComposable
fun smartListOverline(list: SmartList): String {
    val locale = uiLocale()
    return when (list.kind) {
        SmartLists.Kind.Month -> java.time.LocalDate.now().month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        SmartLists.Kind.Mix -> list.artists.take(2).joinToString(", ").ifEmpty {
            pluralStringResource(R.plurals.home_songs_count, list.tracks.size, list.tracks.size)
        }
        SmartLists.Kind.Year -> list.number.toString()
        else -> pluralStringResource(R.plurals.home_songs_count, list.tracks.size, list.tracks.size)
    }.uppercase(locale)
}

/** One hue per kind (the card's own accent, clamped for the theme like the app accent). */
@Composable
@ReadOnlyComposable
fun smartListTint(list: SmartList): Color {
    val raw = when (list.kind) {
        SmartLists.Kind.Month -> AccentPresets.kehribar
        SmartLists.Kind.Best -> AccentPresets.gul
        SmartLists.Kind.Rediscover -> AccentPresets.turkuaz
        SmartLists.Kind.Discoveries -> AccentPresets.nane
        SmartLists.Kind.Forgotten -> AccentPresets.lavanta
        SmartLists.Kind.Mix -> when (list.number % 3) {
            1 -> AccentPresets.gokyuzu
            2 -> AccentPresets.mandalina
            else -> AccentPresets.mercan
        }
        SmartLists.Kind.Year -> AccentPresets.limon
    }
    val x = ShadeTheme.extras
    return clampAccent(raw, x.isDark, x.accentFallback)
}

// ------------------------------------------------------------------ covers

/** Liked Songs cover: accent → surface3 gradient with a heart (SCREENS 5 / 7). */
@Composable
fun LikedCover(size: Dp, modifier: Modifier = Modifier, shape: Shape = ShadeRadius.cover) {
    val accent = ShadeTheme.accent.accent
    val x = ShadeTheme.extras
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent, x.surface3))),
        contentAlignment = Alignment.Center,
    ) {
        ShadeIcon(R.drawable.ic_heart_filled, null, tint = x.text1, size = (size * 0.36f).coerceIn(16.dp, 64.dp))
    }
}

/** Folder card cover: surface2 tile, folder icon and its playlist count (SCREENS 5). */
@Composable
fun FolderCover(size: Dp, count: Int?, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Box(
        modifier.size(size).clip(ShadeRadius.cover).background(x.surface2),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ShadeIcon(R.drawable.ic_folder, null, tint = x.text2, size = (size * 0.3f).coerceIn(16.dp, 48.dp))
            if (count != null && size >= 72.dp) {
                Spacer(Modifier.height(6.dp))
                Text(count.toString(), style = ShadeMono.medium, color = x.text2)
            }
        }
    }
}

fun Card.artworkKind(): ArtworkKind = when {
    shape == CardShape.Circle || placeholder == CardPlaceholder.Artist -> ArtworkKind.Artist
    placeholder == CardPlaceholder.Playlist -> ArtworkKind.Playlist
    else -> ArtworkKind.Album
}

private fun Card.folderCount(): Int? = (subtitle as? CardSubtitle.Playlists)?.count

/** The cover of any library card (Liked gradient, folder tile, or the images). */
@Composable
fun CardCover(card: Card, size: Dp, modifier: Modifier = Modifier) {
    when {
        card.destination == Destination.Liked -> LikedCover(size, modifier)
        card.isFolder -> FolderCover(size, card.folderCount(), modifier)
        else -> {
            val px = with(LocalDensity.current) { size.roundToPx() }
            val url = remember(card.images, px) { card.images.urlFor(px) }
            Artwork(url, modifier, kind = card.artworkKind(), size = size)
        }
    }
}

// ------------------------------------------------------------------ tiles / strips

/**
 * Strip tile (COMPONENTS: Kapak karosu) for a library [Card]: [size] cover · 8 · 12.5/600 title + 11.5 text3
 * subtitle (2 lines). Artists: circle cover, centered text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseTile(
    card: Card,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    onLongClick: (() -> Unit)? = null,
    subtitleOverride: String? = null,
) {
    val x = ShadeTheme.extras
    val center = card.artworkKind() == ArtworkKind.Artist
    val subtitle = subtitleOverride ?: cardSubtitle(card.subtitle)
    Column(
        modifier
            .width(size)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        CardCover(card, size)
        Spacer(Modifier.height(8.dp))
        Text(
            cardTitle(card), style = ShadeTextStyles.tileTitle, color = x.text1, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle, style = ShadeTextStyles.tileSubtitle, color = x.text3, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = if (center) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/**
 * A horizontally scrolling strip of cards (padding 20, spacing 12), preceded by a [SectionHeader] ([seeAll] adds
 * "Tümünü gör"). Titles are resolved inside the items (string resources need a composition).
 */
fun LazyListScope.cardStripSection(
    key: String,
    cards: List<Card>,
    size: Dp,
    onOpen: (Card) -> Unit,
    title: @Composable () -> String,
    subtitle: @Composable () -> String? = { null },
    seeAll: (() -> Unit)? = null,
    onLongClick: ((Card) -> Unit)? = null,
    topSpacing: Dp = 24.dp,
) {
    if (cards.isEmpty()) return
    item(key = "$key-header", contentType = "header") {
        SectionHeader(
            title(), Modifier.padding(top = topSpacing), subtitle = subtitle(),
            actionLabel = if (seeAll != null) stringResource(R.string.home_see_all) else null, onAction = seeAll,
        )
    }
    item(key = "$key-strip", contentType = "strip") {
        CardStrip(cards, size, onOpen, onLongClick = onLongClick)
    }
}

@Composable
fun CardStrip(
    cards: List<Card>,
    size: Dp,
    onOpen: (Card) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((Card) -> Unit)? = null,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards.size, key = { i -> stripKey(cards[i], i) }) { i ->
            val c = cards[i]
            BrowseTile(c, { onOpen(c) }, size = size, onLongClick = onLongClick?.let { { it(c) } })
        }
    }
}

fun stripKey(c: Card, i: Int): String = "$i:" + when (val d = c.destination) {
    Destination.Liked -> "liked"
    is Destination.Playlist -> d.id
    is Destination.Album -> d.id
    is Destination.Artist -> d.id
    is Destination.Library -> "folder:" + d.folderId
    is Destination.SmartList -> "smart:" + d.id
}

/** List row of a library card (Library list mode / search results): 72 high, cover 56 · 12 · title + subtitle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardListRow(
    card: Card,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    subtitleOverride: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardCover(card, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cardTitle(card), style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = subtitleOverride ?: cardSubtitle(card.subtitle)
            if (!sub.isNullOrEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

