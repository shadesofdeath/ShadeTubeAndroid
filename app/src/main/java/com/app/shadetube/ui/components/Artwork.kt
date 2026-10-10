package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.app.shadetube.R
import com.app.shadetube.catalog.Image as CatalogImage
import com.app.shadetube.catalog.pick
import com.app.shadetube.ui.theme.LocalReduceMotion
import com.app.shadetube.ui.theme.LocalShadeAccent
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme

/** What a cover shows while loading / when missing (res/drawable/placeholder_*). Artists are round. */
enum class ArtworkKind(@param:DrawableRes val placeholder: Int) {
    Album(R.drawable.placeholder_album),
    Track(R.drawable.placeholder_album),
    Artist(R.drawable.placeholder_artist),
    Playlist(R.drawable.placeholder_playlist),
    Podcast(R.drawable.placeholder_podcast),
    Radio(R.drawable.placeholder_radio),
}

/**
 * Cover image: Coil AsyncImage, center-crop, the kind's placeholder while loading / on error / for a null URL,
 * crossfade 250 ms (150 with reduce motion). Covers keep sharp 6dp corners; artists are circles.
 * Decorative by default ([contentDescription] null) — the row / tile around it carries the text.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    kind: ArtworkKind = ArtworkKind.Album,
    size: Dp? = null,
    shape: Shape = if (kind == ArtworkKind.Artist) CircleShape else ShadeRadius.cover,
    contentDescription: String? = null,
    alpha: Float = 1f,
) {
    val m = modifier.then(if (size != null) Modifier.size(size) else Modifier).clip(shape).alpha(alpha)
    val placeholder = painterResource(kind.placeholder)
    if (url.isNullOrEmpty() || LocalInspectionMode.current) {
        Image(placeholder, contentDescription, m, contentScale = ContentScale.Crop)
        return
    }
    val context = LocalContext.current
    val fade = if (LocalReduceMotion.current) ShadeMotion.SHORT else ShadeMotion.STANDARD
    val request = remember(url, fade) { ImageRequest.Builder(context).data(url).crossfade(fade).build() }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = m,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
    )
}

/** [Artwork] from catalog images: picks the smallest image at least as wide as the drawn size in pixels. */
@Composable
fun Artwork(
    images: List<CatalogImage>,
    size: Dp,
    modifier: Modifier = Modifier,
    kind: ArtworkKind = ArtworkKind.Album,
    shape: Shape = if (kind == ArtworkKind.Artist) CircleShape else ShadeRadius.cover,
    contentDescription: String? = null,
    alpha: Float = 1f,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val url = remember(images, px) { images.pick(px)?.url }
    Artwork(url, modifier, kind, size, shape, contentDescription, alpha)
}

/** URL of the best image for [sizePx] (null if none). */
fun List<CatalogImage>.urlFor(sizePx: Int): String? = pick(sizePx)?.url

/**
 * Cover glow (README › Kapak glow'u): a radial accent.glow → transparent gradient behind the content, centered 10 %
 * below the middle, radius 0.9 × the smaller side × [spread]. Disabled with reduce motion. [color] defaults to the
 * current accent's glow.
 */
fun Modifier.coverGlow(color: Color = Color.Unspecified, spread: Float = 1f): Modifier =
    this then CoverGlowElement(color, spread)

private data class CoverGlowElement(val color: Color, val spread: Float) : ModifierNodeElement<CoverGlowNode>() {
    override fun create() = CoverGlowNode(color, spread)
    override fun update(node: CoverGlowNode) {
        node.color = color
        node.spread = spread
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "coverGlow"
        properties["color"] = color
        properties["spread"] = spread
    }
}

private class CoverGlowNode(var color: Color, var spread: Float) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        if (!currentValueOf(LocalReduceMotion)) {
            val c = if (color != Color.Unspecified) color else currentValueOf(LocalShadeAccent).glow
            val radius = size.minDimension * 0.9f * spread
            val center = center + Offset(0f, size.height * 0.1f)
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = center, radius = radius), radius, center)
        }
        drawContent()
    }
}

/**
 * Colored drop shadow of hero covers (README: shadow(30.dp, RoundedCornerShape(8.dp), accent.glow) — API 28+ colored
 * shadows). Use together with [coverGlow]; nothing with reduce motion.
 */
@Composable
fun Modifier.coverShadow(elevation: Dp = 30.dp, shape: Shape = ShadeRadius.coverLarge): Modifier {
    if (ShadeTheme.reduceMotion) return this
    val glow = ShadeTheme.accent.glow
    return shadow(elevation, shape, clip = false, ambientColor = glow, spotColor = glow)
}

