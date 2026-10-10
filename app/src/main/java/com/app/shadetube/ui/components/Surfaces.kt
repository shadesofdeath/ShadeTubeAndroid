package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.PillShape
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.bricolage

// ------------------------------------------------------------------ bottom sheet

/** Drag handle: 36×4 `outline`, 14 from the top. */
@Composable
fun SheetDragHandle(modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.sheet_drag_handle_cd)
    Box(
        modifier
            .padding(top = 14.dp, bottom = 6.dp)
            .size(36.dp, 4.dp)
            .clip(PillShape)
            .background(ShadeTheme.extras.outline)
            .semantics { contentDescription = cd },
    )
}

/**
 * Modal bottom sheet (COMPONENTS: Bottom sheet): top corners 28, surface2, drag handle, scrim `scrim`. Content gets
 * a [ColumnScope]; add a [SheetHeader] and [SheetMenuRow]s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadeBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val x = ShadeTheme.extras
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = ShadeRadius.sheet,
        containerColor = x.surface2,
        contentColor = x.text1,
        tonalElevation = 0.dp,
        scrimColor = x.scrim,
        dragHandle = { SheetDragHandle() },
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/** Sheet title: headlineSmall 20, padding 24 horizontal / 20 vertical (less on top under the handle). */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val x = ShadeTheme.extras
    Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = x.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Sheet menu row: 56 high, icon 24 text2 · 16 · bodyLarge text1. [destructive] rows use `error` for both. */
@Composable
fun SheetMenuRow(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    val tint = when { !enabled -> x.text3; destructive -> x.error; else -> x.text2 }
    val text = when { !enabled -> x.text3; destructive -> x.error; else -> x.text1 }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadeIcon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        trailing?.invoke(this)
    }
}

/** Divider between sheet menu groups. */
@Composable
fun SheetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(vertical = 8.dp), thickness = 1.dp, color = ShadeTheme.extras.surface3)
}

// ------------------------------------------------------------------ segmented pill

/**
 * Segmented pill (Now Playing tabs, Radio | Podcast, stats ranges): 44 high pill, surface2, 4dp inner padding,
 * equal segments; the selected one is a surface3 pill (slides 250 ms) with 13/600 text1, others 13 text2.
 */
@Composable
fun SegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = ShadeTheme.extras
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(PillShape)
            .background(x.surface2)
            .padding(4.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segment = maxWidth / count
        val indicatorX by animateDpAsState(
            segment * selectedIndex.coerceIn(0, count - 1),
            ShadeMotion.orReduced(ShadeMotion.standardTween()), label = "segment",
        )
        Box(Modifier.offset(x = indicatorX).width(segment).fillMaxHeight().clip(PillShape).background(x.surface3))
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(PillShape)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = ShadeTextStyles.segment.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (selected) x.text1 else x.text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ snackbar

/**
 * Snackbar host with the ShadeTube style (min 48, surface3, 12 corners, 16 padding, bodyMedium text1, action
 * labelLarge accent). Place it above nav bar + mini player: `Modifier.padding(bottom = ShadeDimens.snackbarBottom)`.
 */
@Composable
fun ShadeSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> ShadeSnackbar(data) }
}

@Composable
fun ShadeSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    ShadeSnackbar(
        message = data.visuals.message,
        actionLabel = data.visuals.actionLabel,
        onAction = { data.performAction() },
        modifier = modifier,
    )
}

@Composable
fun ShadeSnackbar(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(ShadeRadius.card)
            .background(x.surface3)
            .padding(start = 16.dp, end = if (actionLabel != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = x.text1)
        if (actionLabel != null) ShadeTextButton(actionLabel, onAction)
    }
}

// ------------------------------------------------------------------ dialog

/**
 * Dialog (COMPONENTS: Diyalog): surface2, 28 corners, 24 padding, title headlineSmall, body bodyMedium text2,
 * text-button actions ([destructive] confirm in `error`).
 */
@Composable
fun ShadeDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = stringResource(R.string.action_cancel),
    onDismiss: () -> Unit = onDismissRequest,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = ShadeRadius.dialog,
        containerColor = x.surface2,
        titleContentColor = x.text1,
        textContentColor = x.text2,
        tonalElevation = 0.dp,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = if (text == null && content == null) null else ({
            Column {
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
                content?.invoke()
            }
        }),
        confirmButton = { ShadeTextButton(confirmLabel, onConfirm, destructive = destructive) },
        dismissButton = dismissLabel?.let { { ShadeTextButton(it, onDismiss, color = x.text2) } },
    )
}

// ------------------------------------------------------------------ headers / bars

/**
 * Section header (REDLINES: "Son çalınanlar" titleMedium 15/600, optional subtitle 12 text3, "Tümünü gör" labelMedium
 * 12 accent with a 48 hit). Screen-edge padding 20, 10 below.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val x = ShadeTheme.extras
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = x.text3, maxLines = 1)
        }
        if (actionLabel != null && onAction != null) {
            // "Tümünü gör": labelMedium 12 accent (REDLINES), 48 hit.
            ShadeTextButton(actionLabel, onAction, textStyle = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Detail-screen top bar (REDLINES: 48 high, back 48, actions 48, padding 8). Transparent: screens draw their own
 * accent wash behind it. [title] optional (centered column for Now Playing uses [center]).
 */
@Composable
fun ShadeTopBar(
    modifier: Modifier = Modifier,
    @DrawableRes navigationIcon: Int? = R.drawable.ic_arrow_back,
    navigationDescription: String? = stringResource(R.string.common_back_cd),
    onNavigate: (() -> Unit)? = null,
    title: String? = null,
    center: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val x = ShadeTheme.extras
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null && onNavigate != null) {
            ShadeIconButton(navigationIcon, navigationDescription, onNavigate, tint = x.text1, size = 48.dp)
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            when {
                center != null -> center()
                title != null -> Text(
                    title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = x.text1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

// ------------------------------------------------------------------ brand

/** "ShadeTube" wordmark in Bricolage: "Shade" 800 text1 + "Tube" 400 text2, tracking −2/60 em (logo-wordmark.svg). */
@Composable
fun ShadeWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 28.sp) {
    val x = ShadeTheme.extras
    val family = bricolage(fontSize.value)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = x.text1)) { append("Shade") }
            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = x.text2)) { append("Tube") }
        },
        modifier,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = family, fontSize = fontSize, lineHeight = fontSize * 1.15f, letterSpacing = (-2f / 60f).em,
        ),
        maxLines = 1,
    )
}

/** Full logo: crescent mark (accent) + wordmark (logo-full-*.svg proportions: mark 64, gap ~16, text 56). */
@Composable
fun ShadeLogo(modifier: Modifier = Modifier, height: Dp = 40.dp) {
    Row(modifier.widthIn(min = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        CrescentMark(size = height)
        Spacer(Modifier.width(height / 4))
        ShadeWordmark(fontSize = (height.value * 0.875f).sp)
    }
}
