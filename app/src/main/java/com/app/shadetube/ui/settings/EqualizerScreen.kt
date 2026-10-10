package com.app.shadetube.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.playback.dsp.EQ_BANDS
import com.app.shadetube.playback.dsp.EQ_FREQUENCIES
import com.app.shadetube.playback.dsp.EQ_MAX_GAIN_DB
import com.app.shadetube.playback.dsp.EqMath
import com.app.shadetube.ui.components.ShadeFilterChip
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

private const val CUSTOM = "custom"

@Composable
internal fun eqPresetName(id: String): String = stringResource(
    when (id) {
        "flat" -> R.string.eq_preset_flat
        "bass" -> R.string.eq_preset_bass
        "bass-cut" -> R.string.settings_eq_bass_cut
        "treble" -> R.string.settings_eq_treble
        "treble-cut" -> R.string.settings_eq_treble_cut
        "vocal" -> R.string.eq_preset_vocal
        "pop" -> R.string.settings_eq_pop
        "rock" -> R.string.settings_eq_rock
        "hiphop" -> R.string.settings_eq_hiphop
        "electronic" -> R.string.settings_eq_electronic
        "jazz" -> R.string.settings_eq_jazz
        "classical" -> R.string.settings_eq_classical
        "acoustic" -> R.string.settings_eq_acoustic
        "loudness" -> R.string.settings_eq_loudness
        "speech" -> R.string.settings_eq_speech
        else -> R.string.eq_preset_custom
    },
)

private fun bandLabel(f: Float): String = if (f >= 1000f) "${(f / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}k" else f.toInt().toString()

private fun dbLabel(v: Float): String {
    val r = (v * 2).roundToInt() / 2f
    val s = if (r % 1f == 0f) abs(r).toInt().toString() else String.format(Locale.ROOT, "%.1f", abs(r))
    return when {
        r > 0f -> "+$s"
        r < 0f -> "−$s"
        else -> "0"
    }
}

/** Ekolayzer (SCREENS 22): switch, preset chips, the editable curve, band labels, preamp. Writes [SettingsStore]. */
@Composable
fun EqualizerScreen(contentPadding: PaddingValues) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel() }
    val s by vm.settings.collectAsStateWithLifecycle()
    val gains = remember(s.eqGains, s.eqPreset) {
        if (s.eqGains.size == EQ_BANDS) s.eqGains else EqMath.findPreset(s.eqPreset)?.gainsDb ?: List(EQ_BANDS) { 0f }
    }
    val x = ShadeTheme.extras
    fun setBand(i: Int, db: Float) {
        val v = ((db * 2).roundToInt() / 2f).coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB)
        if (gains[i] == v) return
        val next = gains.toMutableList().also { it[i] = v }
        vm.change { it.copy(eqGains = next, eqPreset = CUSTOM, eqEnabled = true) }
    }
    SettingsScaffold(
        stringResource(R.string.eq_title),
        contentPadding,
        actions = {
            ShadeTextButton(
                stringResource(R.string.settings_eq_reset),
                { vm.change { it.copy(eqGains = List(EQ_BANDS) { 0f }, eqPreset = "flat", eqPreampDb = 0f) } },
                color = x.text2,
            )
        },
    ) {
        item(key = "enable") {
            SettingsSwitchRow(
                stringResource(R.string.eq_title), s.eqEnabled, { v -> vm.change { it.copy(eqEnabled = v) } },
                icon = R.drawable.ic_equalizer,
                subtitle = if (s.eqEnabled) eqPresetName(s.eqPreset) else stringResource(R.string.settings_off),
            )
        }
        item(key = "g_presets") { SettingsGroupHeader(stringResource(R.string.eq_presets)) }
        item(key = "presets") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (s.eqPreset == CUSTOM) item(key = CUSTOM) {
                    ShadeFilterChip(stringResource(R.string.eq_preset_custom), selected = true, onClick = {})
                }
                items(EqMath.presets, key = { it.id }) { p ->
                    ShadeFilterChip(
                        eqPresetName(p.id), selected = s.eqPreset == p.id,
                        onClick = { vm.change { it.copy(eqPreset = p.id, eqGains = p.gainsDb, eqEnabled = true) } },
                    )
                }
            }
        }
        item(key = "curve") {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
                EqCurve(
                    gains = gains,
                    enabled = s.eqEnabled,
                    onBandChange = ::setBand,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PreampSlider(
                    valueDb = s.eqPreampDb,
                    enabled = s.eqEnabled,
                    onChange = { v -> vm.change { it.copy(eqPreampDb = ((v * 2).roundToInt() / 2f).coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB)) } },
                )
            }
        }
        item(key = "note") {
            Spacer(Modifier.height(16.dp))
            SettingsNote(stringResource(R.string.settings_eq_note))
        }
    }
}

/**
 * The 10-band curve (README › Ekolayzer eğrisi): 240 high plot + band labels (mono 10) and values (mono 11) below.
 * Catmull-Rom through the band handles → cubicTo, accent @18 % fill below, 2dp accent line, 20dp handles (48 hit
 * area through nearest-band capture); the combined filter response is drawn thin behind it. -12..+12 dB.
 */
@Composable
internal fun EqCurve(
    gains: List<Float>,
    enabled: Boolean,
    onBandChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(gains)
    val onChange by rememberUpdatedState(onBandChange)
    var active by remember { mutableIntStateOf(-1) }
    val lineColor = if (enabled) accent.accent else x.text3
    val fillColor = lineColor.copy(alpha = 0.18f)
    val labelStyle = TextStyle(fontFamily = ShadeMono.small.fontFamily, fontSize = 10.sp, color = x.text3)
    val valueStyle = TextStyle(fontFamily = ShadeMono.small.fontFamily, fontSize = 11.sp, color = x.text2)
    val response = remember(gains) {
        val n = 64
        val lo = ln(EQ_FREQUENCIES.first().toDouble())
        val hi = ln(EQ_FREQUENCIES.last().toDouble())
        List(n) { k ->
            val f = kotlin.math.exp(lo + (hi - lo) * k / (n - 1))
            EqMath.responseDb(gains, f).toFloat()
        }
    }
    val cd = stringResource(R.string.settings_eq_curve_cd, gains.joinToString(", ") { dbLabel(it) })
    Canvas(
        modifier
            .fillMaxWidth()
            .height(240.dp + 40.dp)
            .semantics { contentDescription = cd }
            .pointerInput(Unit) {
                val plotH = 240.dp.toPx()
                val pad = 16.dp.toPx()
                fun bandAt(px: Float): Int {
                    val step = (size.width - 2 * pad) / (EQ_BANDS - 1)
                    return ((px - pad) / step).roundToInt().coerceIn(0, EQ_BANDS - 1)
                }
                fun dbAt(py: Float): Float {
                    val t = ((py - pad) / (plotH - 2 * pad)).coerceIn(0f, 1f)
                    return EQ_MAX_GAIN_DB - t * 2 * EQ_MAX_GAIN_DB
                }
                detectDragGestures(
                    onDragStart = { o -> active = bandAt(o.x); onChange(active, dbAt(o.y)) },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                    onDrag = { change, _ ->
                        change.consume()
                        if (active >= 0) onChange(active, dbAt(change.position.y))
                    },
                )
            }
            .pointerInput(Unit) {
                val plotH = 240.dp.toPx()
                val pad = 16.dp.toPx()
                detectTapGestures { o ->
                    if (o.y > plotH) return@detectTapGestures
                    val step = (size.width - 2 * pad) / (EQ_BANDS - 1)
                    val i = ((o.x - pad) / step).roundToInt().coerceIn(0, EQ_BANDS - 1)
                    val t = ((o.y - pad) / (plotH - 2 * pad)).coerceIn(0f, 1f)
                    onChange(i, EQ_MAX_GAIN_DB - t * 2 * EQ_MAX_GAIN_DB)
                }
            },
    ) {
        val pad = 16.dp.toPx()
        val plotH = 240.dp.toPx()
        val w = size.width - 2 * pad
        val h = plotH - 2 * pad
        fun xOf(i: Int) = pad + w * i / (EQ_BANDS - 1)
        fun yOf(db: Float) = pad + h * (EQ_MAX_GAIN_DB - db) / (2 * EQ_MAX_GAIN_DB)

        // Panel + grid: 0 dB, ±6, ±12; band verticals.
        drawRoundRect(x.surface1, size = Size(size.width, plotH), cornerRadius = CornerRadius(12.dp.toPx()))
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            drawLine(if (db == 0f) x.outline else x.outlineVariant, Offset(pad, yOf(db)), Offset(pad + w, yOf(db)), strokeWidth = 1.dp.toPx())
        }
        for (i in 0 until EQ_BANDS) drawLine(x.outlineVariant, Offset(xOf(i), pad), Offset(xOf(i), pad + h), strokeWidth = 1f)

        // Combined response (thin), on the same log axis.
        val resp = Path()
        response.forEachIndexed { k, db ->
            val px = pad + w * k / (response.size - 1)
            val py = yOf(db.coerceIn(-EQ_MAX_GAIN_DB * 1.5f, EQ_MAX_GAIN_DB * 1.5f)).coerceIn(0f, plotH)
            if (k == 0) resp.moveTo(px, py) else resp.lineTo(px, py)
        }
        drawPath(resp, x.text3.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))

        // Catmull-Rom through the handles.
        val pts = List(EQ_BANDS) { Offset(xOf(it), yOf(gains.getOrElse(it) { 0f })) }
        val curve = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[maxOf(i - 1, 0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[minOf(i + 2, pts.size - 1)]
                val c1 = p1 + (p2 - p0) / 6f
                val c2 = p2 - (p3 - p1) / 6f
                cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
            }
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(pts.last().x, pad + h)
            lineTo(pts.first().x, pad + h)
            close()
        }
        drawPath(fill, fillColor)
        drawPath(curve, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        pts.forEachIndexed { i, p ->
            val r = (if (i == active) 12.dp else 10.dp).toPx()
            drawCircle(x.surface0, r + 2.dp.toPx(), p)
            drawCircle(lineColor, r, p)
        }
        drawBandLabels(measurer, labelStyle, valueStyle, plotH, ::xOf, gains)
    }
}

private fun DrawScope.drawBandLabels(
    measurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
    top: Float,
    xOf: (Int) -> Float,
    gains: List<Float>,
) {
    for (i in 0 until EQ_BANDS) {
        val label = measurer.measure(bandLabel(EQ_FREQUENCIES[i]), labelStyle)
        drawText(label, topLeft = Offset(xOf(i) - label.size.width / 2f, top + 6.dp.toPx()))
        val value = measurer.measure(dbLabel(gains.getOrElse(i) { 0f }), valueStyle)
        drawText(value, topLeft = Offset(xOf(i) - value.size.width / 2f, top + 6.dp.toPx() + label.size.height + 2.dp.toPx()))
    }
}

/** Vertical preamp slider: 48 wide, 240 high, -12..+12 dB, the value in mono above. */
@Composable
internal fun PreampSlider(valueDb: Float, enabled: Boolean, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val current by rememberUpdatedState(onChange)
    val color = if (enabled) accent.accent else x.text3
    val title = stringResource(R.string.eq_preamp)
    Column(modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .width(48.dp)
                .height(240.dp)
                .semantics { contentDescription = "$title ${dbLabel(valueDb)} dB" }
                .pointerInput(Unit) {
                    val pad = 16.dp.toPx()
                    fun dbAt(y: Float): Float {
                        val t = ((y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        return EQ_MAX_GAIN_DB - t * 2 * EQ_MAX_GAIN_DB
                    }
                    detectDragGestures(onDragStart = { current(dbAt(it.y)) }) { change, _ ->
                        change.consume()
                        current(dbAt(change.position.y))
                    }
                }
                .pointerInput(Unit) {
                    val pad = 16.dp.toPx()
                    detectTapGestures { o ->
                        val t = ((o.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        current(EQ_MAX_GAIN_DB - t * 2 * EQ_MAX_GAIN_DB)
                    }
                },
        ) {
            val pad = 16.dp.toPx()
            val h = size.height - 2 * pad
            val cx = size.width / 2
            fun yOf(db: Float) = pad + h * (EQ_MAX_GAIN_DB - db) / (2 * EQ_MAX_GAIN_DB)
            val track = 4.dp.toPx()
            drawRoundRect(x.surface3, Offset(cx - track / 2, pad), Size(track, h), CornerRadius(track / 2))
            val y0 = yOf(0f)
            val yv = yOf(valueDb)
            drawRect(color, Offset(cx - track / 2, minOf(y0, yv)), Size(track, abs(yv - y0)))
            drawLine(x.outline, Offset(cx - 8.dp.toPx(), y0), Offset(cx + 8.dp.toPx(), y0), strokeWidth = 1.dp.toPx())
            drawCircle(x.surface0, 12.dp.toPx(), Offset(cx, yv))
            drawCircle(color, 10.dp.toPx(), Offset(cx, yv))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = ShadeMono.small.copy(fontSize = 10.sp), color = x.text3, maxLines = 1)
        Text(dbLabel(valueDb), style = ShadeMono.small, color = x.text2, maxLines = 1)
    }
}

@Preview(widthDp = 412, heightDp = 360)
@Composable
private fun EqCurvePreview() {
    ShadeTubeThemeOf {
        Row(Modifier.background(ShadeTheme.extras.surface0).padding(12.dp)) {
            EqCurve(EqMath.presets[7].gainsDb, true, { _, _ -> }, Modifier.weight(1f))
            PreampSlider(-2f, true, {})
        }
    }
}
