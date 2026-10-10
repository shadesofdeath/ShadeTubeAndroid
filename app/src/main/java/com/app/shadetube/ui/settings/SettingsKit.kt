package com.app.shadetube.ui.settings

import com.app.shadetube.ui.components.ShadeTextField
import android.content.Context
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeSlider
import com.app.shadetube.ui.components.ShadeSwitch
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/*
 * Building blocks of the profile / settings / stats / downloads / local files screens (SCREENS 18–22, COMPONENTS:
 * "Switch / Slider (Ayarlar)"): group headers in mono overline, 56dp rows (icon 24 text2 · title bodyLarge · value
 * bodyMedium text2 or a switch), slider rows with a mono value, radio rows, choice / text dialogs.
 *
 * `internal` so the other screen packages of the same owner (ui/profile, ui/stats, ui/downloads, ui/localfiles) share
 * them; candidates for promotion to ui/components.
 */

internal val SettingsRowHeight = 56.dp

/** A detail screen: status bar inset + 56 top bar with back, then a LazyColumn that ends above mini player / nav bar. */
@Composable
internal fun SettingsScaffold(
    title: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val nav = LocalNavigator.current
    val x = ShadeTheme.extras
    val dir = LocalLayoutDirection.current
    Column(modifier.fillMaxSize().background(x.surface0)) {
        ShadeTopBar(
            modifier = Modifier.statusBarsPadding(),
            onNavigate = { nav.back() },
            title = title,
            actions = actions,
        )
        header?.invoke(this)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(dir),
                end = contentPadding.calculateEndPadding(dir),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            content = content,
        )
    }
}

/** Group header: overline mono 10, upper case, text3; 20 from the edge, 24 above, 8 below. */
@Composable
internal fun SettingsGroupHeader(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val x = ShadeTheme.extras
    val locale = Locale.current
    Text(
        text.uppercase(java.util.Locale.forLanguageTag(locale.toLanguageTag())),
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        style = ShadeMono.overline,
        color = if (color != Color.Unspecified) color else x.text3,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Settings row: 56 high (grows with font scale), 20 edge padding, optional 24 icon text2 + 16, title bodyLarge text1
 * (+ subtitle bodySmall text2), value bodyMedium text2 at the end, optional [trailing] (switch, button, chevron).
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    value: String? = null,
    valueMono: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    val titleColor = when { !enabled -> x.text3; destructive -> x.error; else -> x.text1 }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            ShadeIcon(icon, null, tint = when { !enabled -> x.text3; destructive -> x.error; else -> x.text2 })
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) x.text2 else x.text3,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrEmpty()) {
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                Modifier.padding(start = 4.dp),
                style = if (valueMono) ShadeMono.medium else MaterialTheme.typography.bodyMedium,
                color = if (enabled) x.text2 else x.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            ShadeIcon(R.drawable.ic_chevron_right, null, tint = x.text3, size = 20.dp)
        }
    }
}

/** A row whose whole surface toggles the switch (a11y: one toggleable element). */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        icon = icon,
        subtitle = subtitle,
        enabled = enabled,
        trailing = { ShadeSwitch(checked, null, enabled = enabled) },
    )
}

/** Title + mono value on one line, the slider below (sliders: value labels in mono). */
@Composable
internal fun SettingsSliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                ShadeIcon(icon, null, tint = if (enabled) x.text2 else x.text3)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) x.text1 else x.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = x.text2)
            }
            Spacer(Modifier.width(12.dp))
            Text(valueLabel, style = ShadeMono.medium, color = if (enabled) x.text1 else x.text3, maxLines = 1)
        }
        ShadeSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(start = if (icon != null) 40.dp else 0.dp),
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/** Radio in the accent (Material geometry). */
@Composable
internal fun ShadeRadio(selected: Boolean, enabled: Boolean = true) {
    val x = ShadeTheme.extras
    RadioButton(
        selected = selected, onClick = null, enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = ShadeTheme.accent.accent, unselectedColor = x.text2,
            disabledSelectedColor = x.text3, disabledUnselectedColor = x.text3,
        ),
    )
}

/** A radio row (one option of a group; put the rows in a `selectableGroup()` column). */
@Composable
internal fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        subtitle = subtitle,
        enabled = enabled,
        // The radio sits at the end so the titles stay aligned with the other rows.
        trailing = { ShadeRadio(selected, enabled) },
    )
}

/** Radio row with the radio button drawn at the start (choice dialogs). */
@Composable
internal fun ChoiceOptionRow(label: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    val x = ShadeTheme.extras
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadeRadio(selected)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = x.text1)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = x.text2)
        }
    }
}

/** Single choice dialog (quality, backup source, storage cap, language…): picking an option applies and closes. */
@Composable
internal fun SettingsChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    subtitles: List<String?> = emptyList(),
    footer: String? = null,
) {
    ShadeDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = null,
        confirmLabel = stringResource(R.string.action_cancel),
        onConfirm = onDismiss,
        dismissLabel = null,
        content = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, label ->
                    ChoiceOptionRow(label, i == selectedIndex, { onSelect(i); onDismiss() }, subtitles.getOrNull(i))
                }
                if (footer != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(footer, style = MaterialTheme.typography.bodySmall, color = ShadeTheme.extras.text2)
                }
            }
        },
    )
}

/** [ShadeTextField] as Settings uses it: surface3 fill (on a surface2 dialog / card), outline border, no clear button. */
@Composable
internal fun settingsFieldColors() = ShadeTheme.extras.let { it.surface3 to it.outline }


/** Dialog with one text field (backup instance URL, …). */
@Composable
internal fun SettingsTextDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    ShadeDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = body,
        confirmLabel = stringResource(R.string.action_ok),
        onConfirm = { onConfirm(text.trim()); onDismiss() },
        content = {
            Spacer(Modifier.height(12.dp))
            val (fill, line) = settingsFieldColors()
            ShadeTextField(
                text, { text = it }, hint = hint, keyboardType = keyboardType, mono = true, clearable = false,
                containerColor = fill, borderColor = line, focusHighlight = false,
            )
        },
    )
}

/** Card on the stage: surface2, 1dp surface3 border, 12 corners, 20 from the screen edges. */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val x = ShadeTheme.extras
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(ShadeRadius.card)
            .background(x.surface2)
            .border(1.dp, x.surface3, ShadeRadius.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Small info line (notes under a group): bodySmall text2 with an info icon. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_info, color: Color = Color.Unspecified) {
    val x = ShadeTheme.extras
    val c = if (color != Color.Unspecified) color else x.text2
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        ShadeIcon(icon, null, tint = c, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c)
    }
}

/** "1,2 GB" in the UI language. */
internal fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

/** "12 sa 34 dk" / "34 dk" from the shared duration strings. */
@Composable
internal fun durationText(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) stringResource(R.string.duration_h_m, h.toInt(), m.toInt()) else stringResource(R.string.duration_m, m.toInt())
}

@Composable
internal fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(ShadeTheme.extras.surface3))
}

@Preview(widthDp = 412)
@Composable
private fun SettingsKitPreview() {
    ShadeTubeThemeOf {
        Column(Modifier.background(ShadeTheme.extras.surface0)) {
            SettingsGroupHeader("Oynatma")
            SettingsRow("Ses kalitesi", icon = R.drawable.ic_headphones, value = "Yüksek", onClick = {})
            SettingsSwitchRow("Sonrakini önceden yükle", true, {}, icon = R.drawable.ic_next)
            SettingsSliderRow("Crossfade", 4f, "4 sn", {}, valueRange = 0f..12f, steps = 11)
            SettingsNote("MP3 320 kbps · etiketler, kapak ve sözlerle kaydedilir.")
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {}
        }
    }
}
