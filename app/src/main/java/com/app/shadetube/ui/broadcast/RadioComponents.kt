package com.app.shadetube.ui.broadcast

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.internetradio.RadioCountry
import com.app.shadetube.internetradio.RadioModel
import com.app.shadetube.internetradio.Station
import com.app.shadetube.library.thousands
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.NowPlayingBars
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/**
 * A station row (60, SCREENS 16): favicon 44 (radio placeholder), name, "Country · genres" (the live title or
 * "Bağlanıyor…" when it is the playing station), codec badge in mono, favorite heart 40 (48 hit).
 */
@Composable
internal fun StationRow(
    station: Station,
    favorite: Boolean,
    now: NowPlayingInfo,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val current = now.trackId == RadioModel.stationId(station.uuid)
    val subtitle = when {
        current && now.connecting && !now.playing -> stringResource(R.string.radio_connecting)
        current && now.liveTitle.isNotEmpty() -> now.liveTitle
        else -> RadioModel.subtitle(station, InternetRadio.labels)
    }
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    TrackRow(
        title = station.name,
        subtitle = subtitle,
        artworkUrl = station.favicon.ifEmpty { null },
        modifier = modifier,
        duration = RadioModel.codecBadge(station).ifEmpty { null },
        isCurrent = current,
        isPlaying = current && now.playing,
        artworkKind = ArtworkKind.Radio,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 4.dp),
        onClick = onClick,
        onLongClick = onLongClick,
        trailing = {
            ShadeIconButton(
                if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                stringResource(if (favorite) R.string.broadcast_radio_unfavorite_cd else R.string.broadcast_radio_favorite_cd),
                onToggleFavorite,
                tint = if (favorite) accent.accent else x.text2,
            )
        },
    )
}

/** Favorite station: 72 circle logo (radio placeholder) + name 12.5 under it; the playing one gets an accent ring. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StationCircle(
    station: Station,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    Column(
        modifier
            .width(size + 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(
                station.favicon.ifEmpty { null }, kind = ArtworkKind.Radio, size = size, shape = CircleShape,
                modifier = if (current) Modifier.border(2.dp, accent.accent, CircleShape) else Modifier,
            )
            if (current) {
                Box(Modifier.size(size).clip(CircleShape).background(x.scrim.copy(alpha = 0.5f)))
                NowPlayingBars(playing, size = 20.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            station.name, style = ShadeTextStyles.tileTitle, color = if (current) accent.accent else x.text1,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** Country tile (2 columns, 72 high): surface2, 12 corners, name 2 lines + station count mono; ISO code mono text3. */
@Composable
internal fun CountryTile(country: RadioCountry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    val locale = LocalConfiguration.current.locales[0]
    Row(
        modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(x.surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                InternetRadio.labels.countryName(country.code, country.name),
                style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                pluralStringResource(R.plurals.broadcast_radio_station_count, country.stationCount, thousands(country.stationCount.toLong(), locale)),
                style = ShadeMono.small, color = x.text3, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(country.code, style = ShadeMono.large, color = x.text3)
    }
}

@Composable
internal fun CountryTileSkeleton(modifier: Modifier = Modifier) {
    SkeletonBox(modifier.height(72.dp), RoundedCornerShape(12.dp))
}

@Composable
internal fun StationCircleSkeleton() {
    Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SkeletonBox(Modifier.size(72.dp), CircleShape)
        Spacer(Modifier.height(8.dp))
        SkeletonLine(56.dp)
    }
}

/** A 56 row with a leading icon and a chevron ("Dünyada popüler", "RSS adresiyle ekle"). */
@Composable
internal fun LinkRow(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        com.app.shadetube.ui.components.ShadeIcon(icon, null, tint = x.text2)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        com.app.shadetube.ui.components.ShadeIcon(R.drawable.ic_chevron_right, null, tint = x.text3, size = 20.dp)
    }
}

internal val previewStations = listOf(
    Station(uuid = "1", name = "Radyo Gece", tags = listOf("pop", "news"), countryCode = "TR", country = "Turkey", codec = "MP3", bitrate = 128),
    Station(uuid = "2", name = "Lo-fi Beats 24/7", tags = listOf("lofi"), codec = "AAC+", bitrate = 64),
    Station(uuid = "3", name = "Klasik FM", tags = listOf("classical"), countryCode = "DE", country = "Germany", codec = "AAC", hls = true),
)

@Preview(backgroundColor = 0xFF0B0C10, showBackground = true)
@Composable
private fun StationRowPreview() {
    ShadeTubeThemeOf {
        Column {
            StationRow(previewStations[0], favorite = true, now = NowPlayingInfo("radio:1", true, false, "Sezen Aksu — Gülümse"), onClick = {}, onToggleFavorite = {})
            StationRow(previewStations[1], favorite = false, now = NowPlayingInfo(), onClick = {}, onToggleFavorite = {})
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StationCircle(previewStations[0], current = true, playing = true, onClick = {})
                StationCircle(previewStations[2], current = false, playing = false, onClick = {})
            }
            CountryTile(RadioCountry("TR", "Turkey", 1834), onClick = {}, modifier = Modifier.padding(20.dp).fillMaxWidth())
        }
    }
}
