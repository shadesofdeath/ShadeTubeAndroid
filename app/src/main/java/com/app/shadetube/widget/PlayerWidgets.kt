package com.app.shadetube.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.app.shadetube.MainActivity
import com.app.shadetube.R
import com.app.shadetube.playback.features.SmartShuffle
import com.app.shadetube.ui.shell.ShellRequests
import com.app.shadetube.ui.theme.OnAccentInk
import com.app.shadetube.ui.theme.ShadeDark
import com.app.shadetube.ui.theme.ShadeLight

/**
 * Home-screen widgets (design handoff README "Widget (Glance) ve bildirim", SCREENS #25):
 *  - 4×1: cover 48 + title / artist + previous / play-pause / next.
 *  - 4×2: cover 96 + title / artist + progress + shuffle / previous / play-pause / next / like.
 * Background surface2 with Material You (dynamic colors on Android 12+, the ShadeTube palette below), the accent only
 * on the play button, progress and active toggles. Tapping the widget opens Now Playing; with nothing loaded the
 * widget says "Bir şey çal" and its play button starts something.
 */
class PlayerWidgetSmall : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.prepare(context)
        provideContent {
            val s by WidgetUpdater.state.collectAsState()
            val cover by WidgetUpdater.cover.collectAsState()
            GlanceTheme(colors = WidgetColors.colors) {
                SmallContent(s, cover?.takeIf { it.url == s.coverUrl }?.small)
            }
        }
    }
}

class PlayerWidgetLarge : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.prepare(context)
        provideContent {
            val s by WidgetUpdater.state.collectAsState()
            val cover by WidgetUpdater.cover.collectAsState()
            GlanceTheme(colors = WidgetColors.colors) {
                LargeContent(s, cover?.takeIf { it.url == s.coverUrl }?.large)
            }
        }
    }
}

class PlayerWidgetSmallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidgetSmall()
}

class PlayerWidgetLargeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidgetLarge()
}

/** Material You on Android 12+, else the ShadeTube neutral palette (widgetBackground = secondaryContainer = surface2). */
internal object WidgetColors {
    private val fallback: ColorProviders = ColorProviders(
        light = lightColorScheme(
            primary = ShadeLight.accentFallback,
            onPrimary = Color.White,
            secondaryContainer = ShadeLight.surface2,
            onSecondaryContainer = ShadeLight.text1,
            surface = ShadeLight.surface2,
            onSurface = ShadeLight.text1,
            surfaceVariant = ShadeLight.surface3,
            onSurfaceVariant = ShadeLight.text2,
            outline = ShadeLight.outline,
        ),
        dark = darkColorScheme(
            primary = ShadeDark.accentFallback,
            onPrimary = OnAccentInk,
            secondaryContainer = ShadeDark.surface2,
            onSecondaryContainer = ShadeDark.text1,
            surface = ShadeDark.surface2,
            onSurface = ShadeDark.text1,
            surfaceVariant = ShadeDark.surface3,
            onSurfaceVariant = ShadeDark.text2,
            outline = ShadeDark.outline,
        ),
    )

    val colors: ColorProviders get() = if (Build.VERSION.SDK_INT >= 31) DynamicThemeColorProviders else fallback
}

// ---- layouts ------------------------------------------------------------------------------------------------------------

@Composable
private fun SmallContent(s: WidgetState, cover: Bitmap?) {
    val ctx = LocalContext.current
    val narrow = LocalSize.current.width < 220.dp
    Row(
        modifier = widgetRoot().clickable(openApp(ctx, s.hasTrack)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, 48.dp)
        Spacer(GlanceModifier.width(12.dp))
        Titles(s, GlanceModifier.defaultWeight())
        if (s.hasTrack && !narrow) ControlButton(R.drawable.ic_prev, ctx.getString(R.string.player_prev_cd), command(WidgetAction.PREVIOUS))
        PlayButton(s, 40.dp)
        if (s.hasTrack) ControlButton(R.drawable.ic_next, ctx.getString(R.string.player_next_cd), command(WidgetAction.NEXT))
    }
}

@Composable
private fun LargeContent(s: WidgetState, cover: Bitmap?) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    // Tall enough: the controls get a full-width row of 48 dp targets under the cover row.
    val stacked = size.height >= 172.dp
    val root = widgetRoot().clickable(openApp(ctx, s.hasTrack)).padding(12.dp)
    if (stacked) {
        Column(modifier = root) {
            Row(modifier = GlanceModifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(cover, 96.dp)
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    Titles(s, GlanceModifier.fillMaxWidth(), titleLines = 2)
                    Spacer(GlanceModifier.defaultWeight())
                    Progress(s)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Controls(s, GlanceModifier.fillMaxWidth().height(48.dp))
        }
    } else {
        val coverSize = minOf(96.dp, size.height - 24.dp).coerceAtLeast(48.dp)
        Row(modifier = root, verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, coverSize)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                Titles(s, GlanceModifier.fillMaxWidth())
                Spacer(GlanceModifier.defaultWeight())
                Progress(s)
                Spacer(GlanceModifier.height(4.dp))
                Controls(s, GlanceModifier.fillMaxWidth().height(40.dp))
            }
        }
    }
}

// ---- parts ------------------------------------------------------------------------------------------------------------

/** The widget's surface: rounded (system radius on 12+, 20 dp before), surface2 / Material You background. */
@Composable
private fun widgetRoot(): GlanceModifier {
    val bg = GlanceTheme.colors.widgetBackground
    val base = GlanceModifier.fillMaxSize().appWidgetBackground()
    return if (Build.VERSION.SDK_INT >= 31) {
        base.background(bg).cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        base.background(ImageProvider(R.drawable.widget_background), ContentScale.FillBounds, ColorFilter.tint(bg))
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, size: Dp) {
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null, // the title next to it says what it is
            modifier = GlanceModifier.size(size),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = GlanceModifier.size(size).background(
                ImageProvider(R.drawable.widget_cover_placeholder),
                ContentScale.FillBounds,
                ColorFilter.tint(GlanceTheme.colors.surfaceVariant),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_music_note),
                contentDescription = null,
                modifier = GlanceModifier.size(size / 2),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun Titles(s: WidgetState, modifier: GlanceModifier, titleLines: Int = 1) {
    val ctx = LocalContext.current
    Column(modifier = modifier) {
        Text(
            text = if (s.hasTrack) s.title else ctx.getString(R.string.widget_empty_title),
            maxLines = titleLines,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            text = if (s.hasTrack) s.artist else ctx.getString(R.string.widget_empty_subtitle),
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

@Composable
private fun Progress(s: WidgetState) {
    if (!s.hasTrack || s.live) return
    LinearProgressIndicator(
        progress = s.progress,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

@Composable
private fun Controls(s: WidgetState, modifier: GlanceModifier) {
    val ctx = LocalContext.current
    val c = GlanceTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val cell = GlanceModifier.defaultWeight().fillMaxHeight()
        if (s.hasTrack) {
            val shuffleOn = s.shuffle != SmartShuffle.Mode.Off
            ControlButton(
                if (s.shuffle == SmartShuffle.Mode.Smart) R.drawable.ic_shuffle_smart else R.drawable.ic_shuffle,
                ctx.getString(if (s.shuffle == SmartShuffle.Mode.Smart) R.string.player_shuffle_smart_cd else R.string.player_shuffle_cd),
                command(WidgetAction.SHUFFLE),
                tint = if (shuffleOn) c.primary else c.onSurfaceVariant,
                modifier = cell,
            )
            ControlButton(R.drawable.ic_prev, ctx.getString(R.string.player_prev_cd), command(WidgetAction.PREVIOUS), modifier = cell)
        } else {
            Spacer(cell)
            Spacer(cell)
        }
        Box(modifier = cell, contentAlignment = Alignment.Center) { PlayButton(s, 40.dp) }
        if (s.hasTrack) {
            ControlButton(R.drawable.ic_next, ctx.getString(R.string.player_next_cd), command(WidgetAction.NEXT), modifier = cell)
            if (s.canLike) {
                ControlButton(
                    if (s.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                    ctx.getString(if (s.liked) R.string.player_unlike_cd else R.string.player_like_cd),
                    command(WidgetAction.LIKE),
                    tint = if (s.liked) c.primary else c.onSurfaceVariant,
                    modifier = cell,
                )
            } else {
                Spacer(cell)
            }
        } else {
            Spacer(cell)
            Spacer(cell)
        }
    }
}

@Composable
private fun ControlButton(
    icon: Int,
    description: String,
    action: Action,
    tint: ColorProvider = GlanceTheme.colors.onSurface,
    modifier: GlanceModifier = GlanceModifier.size(40.dp),
) {
    Box(modifier = modifier.clickable(action), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** The accent's only filled shape: a circle with play / pause. */
@Composable
private fun PlayButton(s: WidgetState, size: Dp) {
    val ctx = LocalContext.current
    Box(
        modifier = GlanceModifier.size(size)
            .background(ImageProvider(R.drawable.widget_circle), ContentScale.FillBounds, ColorFilter.tint(GlanceTheme.colors.primary))
            .clickable(command(WidgetAction.PLAY_PAUSE)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = ctx.getString(if (s.playing) R.string.player_pause_cd else R.string.player_play_cd),
            modifier = GlanceModifier.size(size * 0.55f),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
}

private fun command(cmd: String): Action = actionRunCallback<WidgetAction>(actionParametersOf(WidgetAction.COMMAND to cmd))

/** Opens the app: Now Playing when something is loaded. */
private fun openApp(context: Context, nowPlaying: Boolean): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            if (nowPlaying) putExtra(ShellRequests.EXTRA_OPEN_NOW_PLAYING, true)
        },
    )
