package com.app.shadetube.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Covers stay sharp (6), surfaces soften (12 / 20), sheets rise from below (28 top / 0 bottom). tokens.json → radius. */
val ShadeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), // cover
    small = RoundedCornerShape(10.dp), // chip
    medium = RoundedCornerShape(12.dp), // card
    large = RoundedCornerShape(20.dp), // panel
    extraLarge = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), // bottom sheet
)

val PillShape = RoundedCornerShape(50)

object ShadeRadius {
    val cover = RoundedCornerShape(6.dp)
    val coverLarge = RoundedCornerShape(8.dp) // Now Playing 348 cover
    val chip = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(12.dp)
    val miniPlayer = RoundedCornerShape(14.dp)
    val panel = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val dialog = RoundedCornerShape(28.dp)
    val pill = PillShape
}

/** tokens.json → spacing (4dp grid) and size. */
object ShadeDimens {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val section = 24.dp
    val screenEdge = 20.dp

    val touchMin = 48.dp
    val icon = 24.dp
    val iconSmall = 16.dp
    val avatar = 40.dp
    val listRow = 60.dp
    val coverRow = 44.dp
    val coverMini = 48.dp
    val coverTile = 132.dp
    val coverTileSmall = 116.dp
    val coverHero = 148.dp
    val coverNowPlaying = 348.dp
    val playButton = 76.dp
    val chipHeight = 36.dp
    val buttonHeight = 48.dp
    val miniPlayer = 64.dp
    val navBar = 80.dp

    /** Content bottom padding above nav bar + mini player (README: bottom = 80 + 12 for the mini player). */
    val miniPlayerBottom = 92.dp

    /** Snackbars sit above nav bar + mini player: 80 + 64 + 24. */
    val snackbarBottom = 168.dp
}
