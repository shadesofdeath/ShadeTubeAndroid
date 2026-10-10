package com.app.shadetube.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.app.shadetube.R
import com.app.shadetube.stats.StatsHeatmap
import com.app.shadetube.ui.settings.durationText
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.pow

/** Short weekday names in the UI language, Monday first. */
@Composable
internal fun weekdayNames(short: Boolean = true): List<String> {
    val locale = java.util.Locale.forLanguageTag(Locale.current.toLanguageTag())
    return remember(locale, short) {
        DayOfWeek.entries.map { it.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, locale) }
    }
}

/**
 * Day × hour heat map (README › Isı haritası): 7 × 24 Canvas, 2dp gaps, 2dp corners, color
 * `lerp(surface2, accent, value^0.7)`. Weekday labels on the left (mono 10), hours 0 / 6 / 12 / 18 below. A tap on a
 * cell shows a tooltip (Popup) with the weekday, the hour and the listening time.
 */
@Composable
internal fun Heatmap(map: StatsHeatmap, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent.accent
    val days = weekdayNames()
    val fullDays = weekdayNames(short = false)
    var selected by remember(map) { mutableStateOf(-1) }
    val density = LocalDensity.current
    val labelW = 32.dp
    val gap = 2.dp
    val cd = stringResource(R.string.stats_heatmap_cd)
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = cd }) {
        val gridW = maxWidth - labelW
        val cell = (gridW - gap * 23) / 24
        val gridH = cell * 7 + gap * 6
        Column {
            Row {
                Column(Modifier.width(labelW).height(gridH)) {
                    days.forEach { d ->
                        Box(Modifier.height(cell + gap).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            Text(d.take(3), style = ShadeMono.small.copy(fontSize = ShadeMono.overline.fontSize), color = x.text3, maxLines = 1)
                        }
                    }
                }
                Box {
                    Canvas(
                        Modifier
                            .width(gridW)
                            .height(gridH)
                            .pointerInput(map) {
                                detectTapGestures { o ->
                                    val c = cell.toPx() + gap.toPx()
                                    val col = (o.x / c).toInt().coerceIn(0, 23)
                                    val row = (o.y / c).toInt().coerceIn(0, 6)
                                    val idx = row * 24 + col
                                    selected = if (selected == idx) -1 else idx
                                }
                            },
                    ) {
                        val c = cell.toPx()
                        val g = gap.toPx()
                        val r = CornerRadius(2.dp.toPx())
                        val max = map.maxMs.coerceAtLeast(1L).toFloat()
                        for (d in 0 until 7) for (h in 0 until 24) {
                            val v = map.at(d, h) / max
                            // surface3 base: the map sits on a surface2 card, where surface2 cells (the spec colour) would be invisible.
                            val color = if (v <= 0f) x.surface3 else lerp(x.surface3, accent, v.pow(0.7f).coerceIn(0.08f, 1f))
                            val tl = Offset(h * (c + g), d * (c + g))
                            drawRoundRect(color, tl, Size(c, c), r)
                            if (d * 24 + h == selected) {
                                drawRoundRect(x.text1, tl, Size(c, c), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                    if (selected >= 0) {
                        val d = selected / 24
                        val h = selected % 24
                        val px = with(density) { ((cell + gap) * h).roundToPx() }
                        val py = with(density) { ((cell + gap) * d).roundToPx() }
                        Popup(
                            alignment = Alignment.TopStart,
                            offset = IntOffset((px - with(density) { 60.dp.roundToPx() }).coerceAtLeast(0), py - with(density) { 44.dp.roundToPx() }),
                            onDismissRequest = { selected = -1 },
                        ) {
                            Column(
                                Modifier
                                    .clip(ShadeRadius.chip)
                                    .background(x.surface3)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    "${fullDays[d]} · ${"%02d".format(h)}:00",
                                    style = MaterialTheme.typography.labelMedium, color = x.text1,
                                )
                                Text(durationText(map.at(d, h)), style = ShadeMono.small, color = x.text2)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(start = labelW)) {
                listOf(0, 6, 12, 18).forEach { h ->
                    Box(Modifier.width((cell + gap) * 6)) {
                        Text("%02d".format(h), style = ShadeMono.small.copy(fontSize = ShadeMono.overline.fontSize), color = x.text3)
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun HeatmapPreview() {
    val map = StatsHeatmap()
    for (d in 0 until 7) for (h in 0 until 24) map.ms[d * 24 + h] = (((d + 1) * (h % 7 + 1) * 37_000L) % 3_600_000L)
    map.maxMs = map.ms.max()
    ShadeTubeThemeOf {
        Box(Modifier.background(ShadeTheme.extras.surface0).padding(20.dp)) { Heatmap(map) }
    }
}

