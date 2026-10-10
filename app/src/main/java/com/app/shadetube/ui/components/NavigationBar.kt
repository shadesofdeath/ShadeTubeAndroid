package com.app.shadetube.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.ui.theme.PillShape
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme

/** One bottom-nav destination. [progress] (0..1, or null) draws the 2dp download ring; [badge] the 6dp accent dot. */
@Immutable
data class ShadeNavItem(
    val label: String,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
    val badge: Boolean = false,
    val progress: Float? = null,
    val badgeDescription: String? = null,
)

/** The 4 top-level tabs (README §2): Home · Search · Library · Broadcast (radio + podcasts). */
enum class ShadeTab(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    Home(R.string.nav_home, R.drawable.ic_home, R.drawable.ic_home_filled),
    Search(R.string.nav_search, R.drawable.ic_search, R.drawable.ic_search_filled),
    Library(R.string.nav_library, R.drawable.ic_library, R.drawable.ic_library_filled),
    Broadcast(R.string.nav_broadcast, R.drawable.ic_radio, R.drawable.ic_radio_filled),
}

/**
 * Bottom navigation for the 4 [ShadeTab]s. [libraryBadge] = new download dot; [libraryProgress] = active download
 * ring on the Library icon (0..1; null hides it).
 */
@Composable
fun ShadeNavigationBar(
    selected: ShadeTab,
    onSelect: (ShadeTab) -> Unit,
    modifier: Modifier = Modifier,
    libraryBadge: Boolean = false,
    libraryProgress: Float? = null,
) {
    val newCd = stringResource(R.string.nav_new_badge_cd)
    val downloadingCd = stringResource(R.string.nav_downloads_active_cd)
    val items = ShadeTab.entries.map { tab ->
        val lib = tab == ShadeTab.Library
        ShadeNavItem(
            label = stringResource(tab.label), icon = tab.icon, selectedIcon = tab.selectedIcon,
            badge = lib && libraryBadge,
            progress = if (lib) libraryProgress else null,
            badgeDescription = when {
                lib && libraryProgress != null -> downloadingCd
                lib && libraryBadge -> newCd
                else -> null
            },
        )
    }
    ShadeNavigationBar(items, selected.ordinal, { onSelect(ShadeTab.entries[it]) }, modifier)
}

/**
 * Navigation bar (COMPONENTS: Navigation bar): 80 + system inset, equal-width items; icon 24, selected indicator
 * 64×32 pill in accent.container with the filled icon in accent; label labelSmall 11/16, selected 600 text1,
 * unselected 500 text2 with the line icon. Pressed: indicator darkens 12 %. Selected items carry `selected = true`.
 */
@Composable
fun ShadeNavigationBar(
    items: List<ShadeNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = ShadeTheme.extras.surface0,
) {
    Box(modifier.fillMaxWidth().background(containerColor).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(80.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, item ->
                NavItem(item, i == selectedIndex, { onSelect(i) }, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun NavItem(item: ShadeNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier, indicatorWidth: androidx.compose.ui.unit.Dp = 64.dp) {
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val interaction = rememberInteractionSource()
    val pressed by interaction.collectIsPressedAsState()
    val pressOverlay = x.text1.copy(alpha = 0.12f)
    val indicatorTarget = when {
        selected && pressed -> pressOverlay.compositeOver(accent.container)
        selected -> accent.container
        pressed -> pressOverlay
        else -> Color.Transparent
    }
    val indicator by animateColorAsState(
        indicatorTarget, tween(ShadeMotion.STANDARD, easing = ShadeMotion.standard), label = "navIndicator",
    )
    val badgeDesc = item.badgeDescription
    Column(
        modifier
            .selectable(
                selected = selected, onClick = onClick, role = Role.Tab,
                interactionSource = interaction, indication = null,
            )
            .then(if (badgeDesc != null) Modifier.semantics { stateDescription = badgeDesc } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(
            Modifier.size(indicatorWidth, 32.dp).clip(PillShape).background(indicator),
            contentAlignment = Alignment.Center,
        ) {
            ShadeIcon(
                if (selected) item.selectedIcon else item.icon, null,
                tint = if (selected) accent.accent else x.text2,
            )
            if (item.progress != null) {
                ProgressRing(item.progress, Modifier.size(30.dp), size = 30.dp, stroke = 2.dp)
            }
            if (item.badge) {
                BadgeDot(Modifier.align(Alignment.Center).offset(x = 13.dp, y = (-11).dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (selected) x.text1 else x.text2,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Navigation rail (SCREENS #26, expanded windows): 80 wide, [header] on top (the profile avatar), the 4 [ShadeTab]s
 * (indicator 56×32 pill, label under it) and [footer] at the bottom (the vertical mini player). [compact] = a short
 * window (landscape phone): tighter items. Takes the start-side system insets (cutout / 3-button nav) itself.
 */
@Composable
fun ShadeNavigationRail(
    selected: ShadeTab,
    onSelect: (ShadeTab) -> Unit,
    modifier: Modifier = Modifier,
    libraryBadge: Boolean = false,
    libraryProgress: Float? = null,
    compact: Boolean = false,
    containerColor: Color = ShadeTheme.extras.surface0,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val newCd = stringResource(R.string.nav_new_badge_cd)
    val downloadingCd = stringResource(R.string.nav_downloads_active_cd)
    Column(
        modifier
            .fillMaxHeight()
            .background(containerColor)
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (header != null) {
            Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
            header()
        }
        Spacer(Modifier.height(if (compact) 4.dp else 20.dp))
        Column(
            Modifier.weight(1f).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp),
        ) {
            ShadeTab.entries.forEach { tab ->
                val lib = tab == ShadeTab.Library
                val item = ShadeNavItem(
                    label = stringResource(tab.label), icon = tab.icon, selectedIcon = tab.selectedIcon,
                    badge = lib && libraryBadge,
                    progress = if (lib) libraryProgress else null,
                    badgeDescription = when {
                        lib && libraryProgress != null -> downloadingCd
                        lib && libraryBadge -> newCd
                        else -> null
                    },
                )
                NavItem(
                    item, tab == selected, { onSelect(tab) },
                    Modifier.width(80.dp).height(if (compact) 56.dp else 64.dp),
                    indicatorWidth = 56.dp,
                )
            }
        }
        if (footer != null) {
            footer()
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
        }
    }
}
