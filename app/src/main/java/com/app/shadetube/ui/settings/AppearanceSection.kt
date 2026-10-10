package com.app.shadetube.ui.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.R
import com.app.shadetube.core.AccentMode
import com.app.shadetube.core.ThemeMode
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.theme.ACCENT_MIN_CHROMA
import com.app.shadetube.ui.theme.AccentPresets
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import com.app.shadetube.ui.theme.clampAccent
import com.app.shadetube.ui.theme.onAccentFor
import com.app.shadetube.ui.theme.parseHexColor
import com.app.shadetube.ui.theme.toHex
import com.app.shadetube.ui.theme.toOklch
import kotlin.math.abs

private val THEMES = listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.Light)
private val ACCENT_MODES = listOf(AccentMode.Dynamic, AccentMode.Fixed, AccentMode.System)
private val LANGUAGES = listOf("", "tr", "en")

@Composable
internal fun AppearanceSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var languageDialog by remember { mutableStateOf(false) }
    val languageLabels = listOf(
        stringResource(R.string.settings_language_system),
        stringResource(R.string.settings_language_tr),
        stringResource(R.string.settings_language_en),
    )
    val materialYou = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsScaffold(stringResource(R.string.settings_appearance), contentPadding) {
        item(key = "g_theme") { SettingsGroupHeader(stringResource(R.string.settings_theme)) }
        item(key = "theme") {
            SegmentedPill(
                options = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_dark), stringResource(R.string.theme_light)),
                selectedIndex = THEMES.indexOf(s.theme).coerceAtLeast(0),
                onSelect = { i -> vm.change { it.copy(theme = THEMES[i]) } },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item(key = "g_accent") { SettingsGroupHeader(stringResource(R.string.settings_accent_mode)) }
        item(key = "accent_modes") {
            RadioGroup(
                options = listOf(
                    stringResource(R.string.accent_from_artwork) to stringResource(R.string.settings_accent_artwork_sub),
                    stringResource(R.string.accent_fixed) to stringResource(R.string.settings_accent_fixed_sub),
                    stringResource(R.string.accent_material_you) to
                        if (materialYou) stringResource(R.string.settings_accent_system_sub) else stringResource(R.string.settings_accent_system_unavailable),
                ),
                selected = ACCENT_MODES.indexOf(s.accentMode),
                onSelect = { i -> vm.change { it.copy(accentMode = ACCENT_MODES[i]) } },
                enabled = listOf(true, true, materialYou),
            )
        }
        if (s.accentMode == AccentMode.Fixed) {
            item(key = "palette") {
                AccentPicker(
                    hex = s.fixedAccent,
                    onPick = { hex -> vm.change { it.copy(fixedAccent = hex) } },
                )
            }
        }
        item(key = "g_motion") { SettingsGroupHeader(stringResource(R.string.settings_group_motion_language)) }
        item(key = "motion") {
            SettingsSwitchRow(
                stringResource(R.string.settings_reduce_motion), s.reduceMotion, { v -> vm.change { it.copy(reduceMotion = v) } },
                icon = R.drawable.ic_sparkle, subtitle = stringResource(R.string.settings_reduce_motion_sub),
            )
        }
        item(key = "language") {
            SettingsRow(
                stringResource(R.string.settings_language), icon = R.drawable.ic_info,
                value = languageLabels[LANGUAGES.indexOf(s.language).coerceAtLeast(0)],
                onClick = { languageDialog = true },
            )
        }
    }
    if (languageDialog) {
        SettingsChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = languageLabels,
            selectedIndex = LANGUAGES.indexOf(s.language).coerceAtLeast(0),
            onSelect = { i -> vm.setLanguage(LANGUAGES[i]) },
            onDismiss = { languageDialog = false },
        )
    }
}

private fun sameColor(a: String, b: Color): Boolean = parseHexColor(a)?.let { it.toArgb() == b.toArgb() } == true

/**
 * Fixed accent: the 12 presets as 40dp discs (selected = 2dp text1 ring + check) and "Özel" (a hue wheel disc)
 * which opens HSL sliders. Out-of-range colors are clamped by the theme (OKLCH lightness per theme, neutral below
 * chroma 0.06): the applied color is previewed next to the picked one with an explanation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentPicker(hex: String, onPick: (String) -> Unit) {
    val x = ShadeTheme.extras
    val presetIndex = AccentPresets.all.indexOfFirst { sameColor(hex, it.second) }
    var customOpen by remember { mutableStateOf(presetIndex < 0) }
    val customSelected = presetIndex < 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentPresets.all.forEachIndexed { i, (key, color) ->
                AccentDisc(
                    brush = Brush.linearGradient(listOf(color, color)),
                    selected = i == presetIndex,
                    checkColor = onAccentFor(color),
                    description = presetName(key),
                    onClick = { customOpen = false; onPick(color.toHex()) },
                )
            }
            AccentDisc(
                brush = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
                selected = customSelected,
                checkColor = Color.White,
                description = stringResource(R.string.settings_accent_custom),
                onClick = { customOpen = true },
            )
        }
        if (customOpen) {
            Spacer(Modifier.height(16.dp))
            CustomAccentEditor(hex, onPick)
        } else if (presetIndex >= 0) {
            Spacer(Modifier.height(8.dp))
            Text(presetName(AccentPresets.all[presetIndex].first), style = MaterialTheme.typography.bodySmall, color = x.text2)
        }
    }
}

@Composable
private fun presetName(key: String): String = stringResource(
    when (key) {
        "kehribar" -> R.string.settings_accent_kehribar
        "turkuaz" -> R.string.settings_accent_turkuaz
        "gul" -> R.string.settings_accent_gul
        "lavanta" -> R.string.settings_accent_lavanta
        "gokyuzu" -> R.string.settings_accent_gokyuzu
        "nane" -> R.string.settings_accent_nane
        "mandalina" -> R.string.settings_accent_mandalina
        "mercan" -> R.string.settings_accent_mercan
        "limon" -> R.string.settings_accent_limon
        "buz" -> R.string.settings_accent_buz
        "kum" -> R.string.settings_accent_kum
        else -> R.string.settings_accent_gri
    },
)

@Composable
private fun AccentDisc(brush: Brush, selected: Boolean, checkColor: Color, description: String, onClick: () -> Unit) {
    val x = ShadeTheme.extras
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .then(if (selected) Modifier.border(2.dp, x.text1, CircleShape).padding(4.dp) else Modifier)
                .clip(CircleShape)
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) ShadeIcon(R.drawable.ic_check, null, tint = checkColor, size = 18.dp)
        }
    }
}

@Composable
private fun CustomAccentEditor(hex: String, onPick: (String) -> Unit) {
    val x = ShadeTheme.extras
    val start = remember(hex) {
        val out = FloatArray(3)
        ColorUtils.colorToHSL((parseHexColor(hex) ?: Color(0xFFDDFF47)).toArgb(), out)
        out
    }
    var h by remember(hex) { mutableFloatStateOf(start[0]) }
    var sat by remember(hex) { mutableFloatStateOf(start[1]) }
    var light by remember(hex) { mutableFloatStateOf(start[2]) }
    val raw = Color(ColorUtils.HSLToColor(floatArrayOf(h, sat, light)))
    val dark = x.isDark
    val applied = clampAccent(raw, dark, x.accentFallback)
    val neutral = raw.toOklch().c < ACCENT_MIN_CHROMA
    val changed = abs(applied.red - raw.red) + abs(applied.green - raw.green) + abs(applied.blue - raw.blue) > 0.02f
    fun commit() = onPick(raw.toHex())

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(x.surface2)
            .border(1.dp, x.surface3, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(raw, stringResource(R.string.settings_accent_picked))
            Spacer(Modifier.width(16.dp))
            Swatch(applied, stringResource(R.string.settings_accent_applied))
            Spacer(Modifier.weight(1f))
            Text(raw.toHex(), style = ShadeMono.medium, color = x.text2)
        }
        SettingsSliderRow(stringResource(R.string.settings_accent_hue), h, "${h.toInt()}°", { h = it }, valueRange = 0f..360f, onValueChangeFinished = ::commit)
        SettingsSliderRow(stringResource(R.string.settings_accent_saturation), sat, "${(sat * 100).toInt()}%", { sat = it }, onValueChangeFinished = ::commit)
        SettingsSliderRow(stringResource(R.string.settings_accent_lightness), light, "${(light * 100).toInt()}%", { light = it }, onValueChangeFinished = ::commit)
        if (neutral || changed) {
            SettingsNote(
                stringResource(if (neutral) R.string.settings_accent_clamped_neutral else R.string.settings_accent_clamped),
                icon = R.drawable.ic_info,
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    val x = ShadeTheme.extras
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(32.dp).clip(CircleShape)) { drawRect(color) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = x.text2)
    }
}

@Preview(widthDp = 412)
@Composable
private fun AccentPickerPreview() {
    ShadeTubeThemeOf {
        Column(Modifier.background(ShadeTheme.extras.surface0)) {
            AccentPicker("#F0B35A") {}
            AccentPicker("#202020") {}
        }
    }
}
