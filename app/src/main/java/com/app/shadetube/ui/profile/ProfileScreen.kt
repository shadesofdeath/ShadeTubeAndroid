package com.app.shadetube.ui.profile

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.UserProfile
import com.app.shadetube.stats.StatsPeriod
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.components.ProfileAvatar
import com.app.shadetube.ui.settings.SettingsCard
import com.app.shadetube.ui.settings.SettingsGroupHeader
import com.app.shadetube.ui.settings.SettingsRow
import com.app.shadetube.ui.settings.SettingsScaffold
import com.app.shadetube.ui.settings.formatBytes
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** "Bu hafta" numbers (last 7 days). */
data class WeekSummary(val loaded: Boolean = false, val listenedMs: Long = 0, val streams: Int = 0, val topArtist: String = "")

class ProfileViewModel : ViewModel() {
    val sessionState: StateFlow<SessionState> = AppGraph.session.state
    val profile: StateFlow<UserProfile> = AppGraph.session.profile

    val week: StateFlow<WeekSummary> = AppGraph.stats.version
        .map {
            withContext(AppDispatchers.cpu) {
                val s = AppGraph.stats.summarize(StatsPeriod.Week, topN = 1, recentN = 0)
                WeekSummary(AppGraph.stats.store.isLoaded, s.listenedMs, s.streams, s.topArtists.firstOrNull()?.artist?.name.orEmpty())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeekSummary())

    data class Counts(val activeDownloads: Int = 0, val downloadedBytes: Long = 0, val localTracks: Int = 0)

    val counts: StateFlow<Counts> = combine(AppGraph.downloads.state, AppGraph.localLibrary.flow) { d, l ->
        Counts(d.activeCount, d.totalBytes, l.tracks.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Counts())

    fun logout() = AppGraph.session.logout()
}

/** Profil: avatar + name, "Bu hafta" card, entries to Stats / Downloads / Local files / Blocklist / Settings, login / logout. */
@Composable
fun ProfileScreen(contentPadding: PaddingValues) {
    val vm: ProfileViewModel = viewModel { ProfileViewModel() }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val state by vm.sessionState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val x = ShadeTheme.extras

    SettingsScaffold(
        stringResource(R.string.profile_title),
        contentPadding,
        actions = {
            ShadeIconButton(
                R.drawable.ic_settings, stringResource(R.string.settings_title),
                { nav.navigate(Screen.Settings()) }, tint = x.text1, size = 48.dp,
            )
        },
    ) {
        item(key = "header") {
            ProfileHeader(state, profile, onLogin = { nav.startSpotifyLogin() })
        }
        item(key = "week") {
            WeekCard(week, onClick = { nav.navigate(Screen.Stats) })
        }
        item(key = "g_library") { SettingsGroupHeader(stringResource(R.string.profile_group_library)) }
        item(key = "stats") {
            SettingsRow(stringResource(R.string.stats_title), icon = R.drawable.ic_stats, chevron = true, onClick = { nav.navigate(Screen.Stats) })
        }
        item(key = "downloads") {
            SettingsRow(
                stringResource(R.string.downloads_title), icon = R.drawable.ic_download,
                value = downloadsValue(context, counts), valueMono = counts.activeDownloads == 0,
                chevron = true, onClick = { nav.navigate(Screen.Downloads) },
            )
        }
        item(key = "local") {
            SettingsRow(
                stringResource(R.string.local_title), icon = R.drawable.ic_folder,
                value = if (counts.localTracks > 0) pluralStringResource(R.plurals.profile_songs, counts.localTracks, counts.localTracks) else null,
                chevron = true, onClick = { nav.navigate(Screen.LocalFiles) },
            )
        }
        item(key = "blocked") {
            SettingsRow(stringResource(R.string.settings_blocked), icon = R.drawable.ic_block, chevron = true, onClick = { nav.navigate(Screen.Blocklist) })
        }
        item(key = "g_app") { SettingsGroupHeader(stringResource(R.string.profile_group_app)) }
        item(key = "settings") {
            SettingsRow(stringResource(R.string.settings_title), icon = R.drawable.ic_settings, chevron = true, onClick = { nav.navigate(Screen.Settings()) })
        }
        item(key = "eq") {
            SettingsRow(stringResource(R.string.settings_equalizer), icon = R.drawable.ic_equalizer, chevron = true, onClick = { nav.navigate(Screen.Equalizer) })
        }
        item(key = "auth") {
            Spacer(Modifier.height(8.dp))
            when (state) {
                SessionState.LoggedIn -> SettingsRow(
                    stringResource(R.string.settings_sign_out), icon = R.drawable.ic_logout, destructive = true,
                    onClick = { confirmLogout = true },
                )
                SessionState.Connecting -> SettingsRow(stringResource(R.string.settings_spotify_connecting), icon = R.drawable.ic_user, enabled = false)
                SessionState.LoggedOut -> SettingsRow(
                    stringResource(R.string.settings_spotify_connect), icon = R.drawable.ic_user,
                    onClick = { nav.startSpotifyLogin() },
                )
            }
        }
    }
    if (confirmLogout) {
        ShadeDialog(
            onDismissRequest = { confirmLogout = false },
            title = stringResource(R.string.settings_logout_confirm_title),
            text = stringResource(R.string.settings_logout_confirm_body),
            confirmLabel = stringResource(R.string.settings_sign_out),
            onConfirm = { confirmLogout = false; vm.logout() },
            destructive = true,
        )
    }
}

private fun downloadsValue(context: Context, c: ProfileViewModel.Counts): String? = when {
    c.activeDownloads > 0 -> context.resources.getQuantityString(R.plurals.profile_downloads_active, c.activeDownloads, c.activeDownloads)
    c.downloadedBytes > 0 -> formatBytes(context, c.downloadedBytes)
    else -> null
}

@Composable
private fun ProfileHeader(state: SessionState, profile: UserProfile, onLogin: () -> Unit) {
    val x = ShadeTheme.extras
    val loggedIn = state == SessionState.LoggedIn
    val name = if (loggedIn) profile.name.ifEmpty { profile.username } else ""
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(140.dp).coverGlow(), contentAlignment = Alignment.Center) {
            ProfileAvatar(if (loggedIn) profile.imageUrl else "", name, size = 96.dp)
        }
        Text(
            name.ifEmpty { stringResource(R.string.profile_guest) },
            style = MaterialTheme.typography.headlineMedium, color = x.text1, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (state) {
                SessionState.LoggedIn -> stringResource(R.string.settings_spotify_account)
                SessionState.Connecting -> stringResource(R.string.settings_spotify_connecting)
                SessionState.LoggedOut -> stringResource(R.string.profile_guest_body)
            },
            style = MaterialTheme.typography.bodyMedium, color = x.text2, textAlign = TextAlign.Center,
        )
        if (state == SessionState.LoggedOut) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(stringResource(R.string.settings_spotify_connect), onLogin)
        }
    }
}

/** "Bu hafta" card: mono overline in the accent, the week's listening time big in mono, streams + top artist. */
@Composable
internal fun WeekCard(week: WeekSummary, onClick: () -> Unit) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val locale = androidx.compose.ui.text.intl.Locale.current
    SettingsCard(Modifier.padding(top = 8.dp), onClick = onClick) {
        Text(
            stringResource(R.string.home_this_week_stats).uppercase(java.util.Locale.forLanguageTag(locale.toLanguageTag())),
            style = ShadeMono.overline, color = accent.accent,
        )
        Spacer(Modifier.height(8.dp))
        if (!week.loaded) {
            SkeletonLine(160.dp, height = 28.dp)
            Spacer(Modifier.height(8.dp))
            SkeletonLine(120.dp)
            return@SettingsCard
        }
        val totalMin = week.listenedMs / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        val hUnit = stringResource(R.string.profile_unit_h)
        val mUnit = stringResource(R.string.profile_unit_min)
        Text(
            buildAnnotatedString {
                if (h > 0) {
                    append(h.toString())
                    withStyle(SpanStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize, color = x.text2)) { append(" $hUnit ") }
                }
                append(m.toString())
                withStyle(SpanStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize, color = x.text2)) { append(" $mUnit") }
            },
            style = ShadeMono.display.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize, lineHeight = MaterialTheme.typography.headlineLarge.lineHeight),
            color = x.text1, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_plays, week.streams), style = ShadeMono.small, color = x.text2)
            if (week.topArtist.isNotEmpty()) {
                Text("·", style = ShadeMono.small, color = x.text3)
                Text(
                    stringResource(R.string.profile_top_artist, week.topArtist),
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = x.text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (week.streams == 0) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.profile_week_empty), style = MaterialTheme.typography.bodySmall, color = x.text3)
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun ProfilePreview() {
    ShadeTubeThemeOf {
        Column(Modifier.background(ShadeTheme.extras.surface0)) {
            ProfileHeader(SessionState.LoggedIn, UserProfile(id = "x", name = "Deniz"), {})
            WeekCard(WeekSummary(true, 4 * 3_600_000L + 12 * 60_000L, 38, "Gece Sürüşü"), {})
            Spacer(Modifier.width(8.dp))
        }
    }
}
