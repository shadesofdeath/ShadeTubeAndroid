package com.app.shadetube.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme

/**
 * Horizontal-strip cover tile (COMPONENTS: Kapak karosu): [size] cover (132, or 116 for the small strips), 6dp
 * corners, · 8 · title 12.5/600 (1 line) + subtitle 11.5 text3 (2 lines). Strips: padding 20, spacing 12.
 * Artists get a circle and centered text.
 */
@Composable
fun CoverTile(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    kind: ArtworkKind = ArtworkKind.Album,
    onLongClick: (() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    val center = kind == ArtworkKind.Artist
    Column(
        modifier
            .width(size)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(artworkUrl, kind = kind, size = size)
        Spacer(Modifier.height(8.dp))
        Text(
            title, style = ShadeTextStyles.tileTitle, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
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
 * "Son çalınanlar" tile: 56 high, surface2, corner 10, cover 44 (6dp inset) · 10 · title 13/600. Laid out in 2
 * columns with 8dp gaps (use `Modifier.weight(1f)`).
 */
@Composable
fun RecentTile(
    title: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ArtworkKind = ArtworkKind.Album,
    isPlaying: Boolean = false,
) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(x.surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUrl, kind = kind, size = 44.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            title, Modifier.weight(1f), style = ShadeTextStyles.recentTitle,
            color = if (isPlaying) ShadeTheme.accent.accent else x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (isPlaying) {
            NowPlayingBars(true, Modifier.padding(horizontal = 6.dp))
        }
    }
}

/**
 * "Senin için yapıldı" card: 168×104, surface2, 1dp surface3 border, corner 12, padding 12. Top: mono overline 10
 * in the card's own [tint]; bottom: 17/700 title (2 lines). Top-right: the crescent mark (a 90dp circle minus an
 * offset 90dp circle) in [tint], clipped by the card.
 */
@Composable
fun MadeForYouCard(
    overline: String,
    title: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    height: Dp = 104.dp,
) {
    val x = ShadeTheme.extras
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(x.surface2)
            .border(1.dp, x.surface3, shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCardCrescent(tint) }
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                overline.uppercase(java.util.Locale.forLanguageTag(Locale.current.toLanguageTag())),
                style = ShadeMono.overline, color = tint, maxLines = 1,
            )
            Text(
                title, Modifier.fillMaxWidth(0.72f), style = ShadeTextStyles.cardTitle, color = x.text1, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun DrawScope.drawCardCrescent(tint: Color) {
    val r = 45.dp.toPx()
    val c = Offset(size.width - 18.dp.toPx(), 20.dp.toPx())
    val cut = c + Offset(8.dp.toPx(), -10.dp.toPx())
    val a = Path().apply { addOval(Rect(c, r)) }
    val b = Path().apply { addOval(Rect(cut, r)) }
    drawPath(Path.combine(PathOperation.Difference, a, b), tint)
}

/**
 * The brand crescent (logo mark): big circle minus offset circle minus the record-hole dot, in a 64-unit box.
 * Same geometry as res/drawable/logo_mark.xml; drawn in code so it can take any color (accent, ink, white).
 */
@Composable
fun CrescentMark(modifier: Modifier = Modifier, size: Dp = 64.dp, color: Color = ShadeTheme.accent.accent) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 64f
        fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(Offset(cx * k, cy * k), r * k)) }
        val crescent = Path.combine(PathOperation.Difference, circle(32f, 32f, 26f), circle(41f, 26f, 21f))
        drawPath(Path.combine(PathOperation.Difference, crescent, circle(24f, 38f, 5f)), color)
    }
}
