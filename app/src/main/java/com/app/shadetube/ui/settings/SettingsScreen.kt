package com.app.shadetube.ui.settings

import com.app.shadetube.ui.components.ProfileAvatar
import com.app.shadetube.ui.components.ShadeTextField
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.core.Settings
import com.app.shadetube.scrobble.LastfmErrorKind
import com.app.shadetube.scrobble.ScrobbleError
import com.app.shadetube.scrobble.ScrobbleState
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.spotify.UserProfile
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.SettingsSection
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/** Settings (SCREENS 21): the root list or one of its sections. */
@Composable
fun SettingsScreen(contentPadding: PaddingValues, section: SettingsSection) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel() }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            if (m == SettingsMessage.LanguageChanged) context.findActivity()?.recreate() else nav.message(messageText(context, m))
        }
    }
    when (section) {
        SettingsSection.Root -> SettingsRoot(vm, contentPadding)
        SettingsSection.Account -> AccountSection(vm, contentPadding, showSpotify = true)
        SettingsSection.Connections -> AccountSection(vm, contentPadding, showSpotify = false)
        SettingsSection.Playback -> PlaybackSection(vm, contentPadding)
        SettingsSection.Sound -> SoundSection(vm, contentPadding)
        SettingsSection.Appearance -> AppearanceSection(vm, contentPadding)
        SettingsSection.Downloads -> DownloadsSection(vm, contentPadding)
        SettingsSection.LocalMusic -> LocalMusicSection(vm, contentPadding)
        SettingsSection.Storage -> StorageSection(vm, contentPadding)
        SettingsSection.About -> AboutSection(vm, contentPadding)
        SettingsSection.Licenses -> LicensesSection(contentPadding)
    }
}

private fun messageText(context: Context, m: SettingsMessage): String = when (m) {
    is SettingsMessage.CacheCleared -> context.getString(R.string.settings_cache_cleared)
    is SettingsMessage.LastfmConnected -> context.getString(R.string.settings_lastfm_connected_as, m.user)
    is SettingsMessage.ListenBrainzConnected -> context.getString(R.string.settings_listenbrainz_connected_as, m.user)
    SettingsMessage.KeysSaved -> context.getString(R.string.settings_lastfm_keys_saved)
    SettingsMessage.LanguageChanged -> ""
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens [url] in the browser (Last.fm approval, GitHub, ListenBrainz settings). */
internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

// ------------------------------------------------------------------------------------------------------------- root

@Composable
private fun SettingsRoot(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val state by vm.sessionState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val scrobble by vm.scrobble.collectAsStateWithLifecycle()
    fun open(section: SettingsSection) = nav.navigate(Screen.Settings(section))
    SettingsScaffold(stringResource(R.string.settings_title), contentPadding) {
        item(key = "account") {
            Spacer(Modifier.height(8.dp))
            AccountCard(
                state = state, profile = profile,
                onClick = { open(SettingsSection.Account) },
                onLogin = { nav.startSpotifyLogin() },
            )
        }
        item(key = "g_account") { SettingsGroupHeader(stringResource(R.string.settings_group_account)) }
        item(key = "connections") {
            SettingsRow(
                stringResource(R.string.settings_connections), icon = R.drawable.ic_link,
                value = connectionsSummary(scrobble), chevron = true, onClick = { open(SettingsSection.Connections) },
            )
        }
        item(key = "g_listen") { SettingsGroupHeader(stringResource(R.string.settings_group_listening)) }
        item(key = "playback") {
            SettingsRow(stringResource(R.string.settings_playback), icon = R.drawable.ic_play, chevron = true, onClick = { open(SettingsSection.Playback) })
        }
        item(key = "sound") {
            SettingsRow(stringResource(R.string.settings_sound), icon = R.drawable.ic_volume_high, chevron = true, onClick = { open(SettingsSection.Sound) })
        }
        item(key = "eq") {
            SettingsRow(
                stringResource(R.string.settings_equalizer), icon = R.drawable.ic_equalizer,
                value = stringResource(if (s.eqEnabled) R.string.settings_on else R.string.settings_off),
                chevron = true, onClick = { nav.navigate(Screen.Equalizer) },
            )
        }
        item(key = "blocked") {
            SettingsRow(stringResource(R.string.settings_blocked), icon = R.drawable.ic_block, chevron = true, onClick = { nav.navigate(Screen.Blocklist) })
        }
        item(key = "g_app") { SettingsGroupHeader(stringResource(R.string.settings_group_app)) }
        item(key = "appearance") {
            SettingsRow(stringResource(R.string.settings_appearance), icon = R.drawable.ic_palette, chevron = true, onClick = { open(SettingsSection.Appearance) })
        }
        item(key = "downloads") {
            SettingsRow(stringResource(R.string.settings_download), icon = R.drawable.ic_download, chevron = true, onClick = { open(SettingsSection.Downloads) })
        }
        item(key = "local") {
            SettingsRow(stringResource(R.string.settings_local_music), icon = R.drawable.ic_folder, chevron = true, onClick = { open(SettingsSection.LocalMusic) })
        }
        item(key = "storage") {
            SettingsRow(stringResource(R.string.settings_storage), icon = R.drawable.ic_storage, chevron = true, onClick = { open(SettingsSection.Storage) })
        }
        item(key = "g_about") { SettingsGroupHeader(stringResource(R.string.settings_group_project)) }
        item(key = "about") {
            SettingsRow(
                stringResource(R.string.settings_about), icon = R.drawable.ic_info, value = vm.currentVersion, valueMono = true,
                chevron = true, onClick = { open(SettingsSection.About) },
            )
        }
    }
}

@Composable
private fun connectionsSummary(s: ScrobbleState): String {
    val parts = buildList {
        if (s.lastfmConnected) add(stringResource(R.string.settings_lastfm))
        if (s.listenbrainzConnected) add(stringResource(R.string.settings_listenbrainz))
    }
    return if (parts.isEmpty()) stringResource(R.string.settings_not_connected) else parts.joinToString(" · ")
}


/**
 * Profile card (SCREENS 21): 88 high, avatar 56 + name + "Spotify hesabı" (logged in) or "Misafir" with a
 * "Spotify'a bağlan" pill (logged out).
 */
@Composable
internal fun AccountCard(
    state: SessionState,
    profile: UserProfile,
    onClick: (() -> Unit)?,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = ShadeTheme.extras
    val loggedIn = state == SessionState.LoggedIn
    val name = if (loggedIn) profile.name.ifEmpty { profile.username } else ""
    SettingsCard(modifier.heightIn(min = 88.dp), padding = 16.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(if (loggedIn) profile.imageUrl else "", name, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifEmpty { stringResource(R.string.profile_guest) },
                    style = MaterialTheme.typography.titleLarge, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (state) {
                        SessionState.LoggedIn -> stringResource(R.string.settings_spotify_account)
                        SessionState.Connecting -> stringResource(R.string.settings_spotify_connecting)
                        SessionState.LoggedOut -> stringResource(R.string.settings_spotify_not_connected)
                    },
                    style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 2,
                )
            }
            if (state == SessionState.LoggedOut) {
                Spacer(Modifier.width(8.dp))
                com.app.shadetube.ui.components.SmallPillButton(stringResource(R.string.settings_spotify_connect), onLogin, primary = true)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- account / connections

@Composable
private fun AccountSection(vm: SettingsViewModel, contentPadding: PaddingValues, showSpotify: Boolean) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val state by vm.sessionState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val scrobble by vm.scrobble.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lastfmError by vm.lastfmError.collectAsStateWithLifecycle()
    val lbError by vm.listenbrainzError.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmLastfmOff by remember { mutableStateOf(false) }
    var confirmLbOff by remember { mutableStateOf(false) }

    SettingsScaffold(
        stringResource(if (showSpotify) R.string.settings_account else R.string.settings_connections),
        contentPadding,
    ) {
        if (showSpotify) {
            item(key = "card") {
                Spacer(Modifier.height(8.dp))
                AccountCard(state, profile, onClick = null, onLogin = { nav.startSpotifyLogin() })
            }
            if (state == SessionState.LoggedIn) {
                if (profile.username.isNotEmpty()) item(key = "user") {
                    SettingsRow(stringResource(R.string.settings_spotify_username), icon = R.drawable.ic_user, value = profile.username, valueMono = true)
                }
                item(key = "logout") {
                    SettingsRow(
                        stringResource(R.string.settings_sign_out), icon = R.drawable.ic_logout, destructive = true,
                        onClick = { confirmLogout = true },
                    )
                }
            }
            item(key = "spnote") { SettingsNote(stringResource(R.string.settings_spotify_note), icon = R.drawable.ic_lock) }
        }
        item(key = "g_scrobble") { SettingsGroupHeader(stringResource(R.string.settings_scrobbling)) }
        item(key = "scrobble") {
            SettingsSwitchRow(
                stringResource(R.string.settings_scrobble_enabled), s.scrobbleEnabled, vm::setScrobbleEnabled,
                icon = R.drawable.ic_history, subtitle = stringResource(R.string.settings_scrobble_enabled_sub),
            )
        }
        item(key = "lastfm") {
            LastfmCard(
                state = scrobble,
                busy = busy == SettingsViewModel.BUSY_LASTFM,
                error = lastfmError,
                onSaveKeys = vm::saveLastfmKeys,
                onBegin = { vm.beginLastfm { url -> openUrl(context, url) } },
                onFinish = vm::finishLastfm,
                onDisconnect = { confirmLastfmOff = true },
                onCancelPending = vm::disconnectLastfm,
                onOpenApiPage = { openUrl(context, "https://www.last.fm/api/account/create") },
            )
        }
        item(key = "lb") {
            Spacer(Modifier.height(16.dp))
            ListenBrainzCard(
                state = scrobble,
                busy = busy == SettingsViewModel.BUSY_LISTENBRAINZ,
                error = lbError,
                onSave = vm::setListenBrainzToken,
                onDisconnect = { confirmLbOff = true },
                onOpenSettings = { openUrl(context, "https://listenbrainz.org/settings/") },
            )
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
    if (confirmLastfmOff) {
        ShadeDialog(
            onDismissRequest = { confirmLastfmOff = false },
            title = stringResource(R.string.settings_disconnect_confirm_title, stringResource(R.string.settings_lastfm)),
            text = stringResource(R.string.settings_disconnect_confirm_body),
            confirmLabel = stringResource(R.string.settings_disconnect),
            onConfirm = { confirmLastfmOff = false; vm.disconnectLastfm() },
            destructive = true,
        )
    }
    if (confirmLbOff) {
        ShadeDialog(
            onDismissRequest = { confirmLbOff = false },
            title = stringResource(R.string.settings_disconnect_confirm_title, stringResource(R.string.settings_listenbrainz)),
            text = stringResource(R.string.settings_disconnect_confirm_body),
            confirmLabel = stringResource(R.string.settings_disconnect),
            onConfirm = { confirmLbOff = false; vm.disconnectListenBrainz() },
            destructive = true,
        )
    }
}

@Composable
private fun ServiceHeader(name: String, connected: Boolean, user: String, pending: Int) {
    val x = ShadeTheme.extras
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = x.text1)
            Text(
                if (connected) stringResource(R.string.settings_connected_as, user.ifEmpty { "—" }) else stringResource(R.string.settings_not_connected),
                style = MaterialTheme.typography.bodySmall, color = if (connected) ShadeTheme.accent.accent else x.text2,
            )
        }
        if (connected && pending > 0) {
            Text(stringResource(R.string.settings_scrobble_pending, pending), style = ShadeMono.small, color = x.text3)
        }
    }
}

@Composable
private fun scrobbleErrorText(e: ScrobbleError): String = when (e) {
    ScrobbleError.NoKeys -> stringResource(R.string.settings_err_no_keys)
    ScrobbleError.NoPendingAuth -> stringResource(R.string.settings_err_no_pending)
    ScrobbleError.Network -> stringResource(R.string.settings_err_network)
    ScrobbleError.UnexpectedReply -> stringResource(R.string.settings_err_unexpected)
    ScrobbleError.KeyChanged -> stringResource(R.string.settings_err_key_changed)
    ScrobbleError.EmptyToken -> stringResource(R.string.settings_err_empty_token)
    ScrobbleError.InvalidToken -> stringResource(R.string.settings_err_invalid_token)
    is ScrobbleError.ListenBrainzUnavailable -> stringResource(R.string.settings_err_unavailable)
    is ScrobbleError.Lastfm -> when (e.kind) {
        LastfmErrorKind.InvalidApiKey -> stringResource(R.string.settings_err_lastfm_key)
        LastfmErrorKind.BadSignature -> stringResource(R.string.settings_err_lastfm_secret)
        LastfmErrorKind.NotAuthorized -> stringResource(R.string.settings_err_lastfm_not_authorized)
        LastfmErrorKind.TokenExpired -> stringResource(R.string.settings_err_lastfm_expired)
        LastfmErrorKind.Unavailable -> stringResource(R.string.settings_err_unavailable)
        LastfmErrorKind.RateLimited -> stringResource(R.string.settings_err_rate_limited)
        LastfmErrorKind.Other -> e.message.ifEmpty { stringResource(R.string.settings_err_http, e.http) }
    }
}

@Composable
private fun LastfmCard(
    state: ScrobbleState,
    busy: Boolean,
    error: ScrobbleError?,
    onSaveKeys: (String, String) -> Unit,
    onBegin: () -> Unit,
    onFinish: () -> Unit,
    onDisconnect: () -> Unit,
    onCancelPending: () -> Unit,
    onOpenApiPage: () -> Unit,
) {
    val x = ShadeTheme.extras
    var apiKey by rememberSaveable(state.lastfmApiKey) { mutableStateOf(state.lastfmApiKey) }
    var secret by rememberSaveable { mutableStateOf("") }
    SettingsCard {
        ServiceHeader(stringResource(R.string.settings_lastfm), state.lastfmConnected, state.lastfmUser, state.pendingLastfm)
        Spacer(Modifier.height(12.dp))
        when {
            state.lastfmConnected -> {
                ShadeTextButton(stringResource(R.string.settings_disconnect), onDisconnect, destructive = true)
            }
            state.lastfmAuthPending -> {
                Text(stringResource(R.string.settings_lastfm_pending), style = MaterialTheme.typography.bodyMedium, color = x.text2)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(stringResource(R.string.settings_lastfm_finish), onFinish, loading = busy)
                    ShadeTextButton(stringResource(R.string.action_cancel), onCancelPending, color = x.text2)
                }
                ShadeTextButton(stringResource(R.string.settings_lastfm_reopen), onBegin, enabled = !busy)
            }
            else -> {
                Text(stringResource(R.string.settings_lastfm_keys_help), style = MaterialTheme.typography.bodySmall, color = x.text2)
                ShadeTextButton(stringResource(R.string.settings_lastfm_get_keys), onOpenApiPage)
                ShadeTextField(
                    apiKey, { apiKey = it }, hint = stringResource(R.string.settings_lastfm_api_key), mono = true, clearable = false,
                    containerColor = settingsFieldColors().first, borderColor = settingsFieldColors().second, focusHighlight = false,
                )
                Spacer(Modifier.height(8.dp))
                ShadeTextField(
                    secret, { secret = it },
                    hint = stringResource(if (state.lastfmHasSecret) R.string.settings_lastfm_secret_kept else R.string.settings_lastfm_secret),
                    password = true, mono = true, clearable = false,
                    containerColor = settingsFieldColors().first, borderColor = settingsFieldColors().second, focusHighlight = false,
                )
                Spacer(Modifier.height(12.dp))
                val keysChanged = apiKey.trim() != state.lastfmApiKey || secret.isNotBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (keysChanged || !state.lastfmHasKeys) {
                        SecondaryButton(
                            stringResource(R.string.settings_lastfm_save_keys),
                            { onSaveKeys(apiKey, secret); secret = "" },
                            enabled = apiKey.isNotBlank() && (secret.isNotBlank() || state.lastfmHasSecret),
                        )
                    }
                    if (state.lastfmHasKeys && !keysChanged) {
                        PrimaryButton(stringResource(R.string.settings_lastfm_connect), onBegin, loading = busy, icon = R.drawable.ic_external_link)
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(scrobbleErrorText(error), style = MaterialTheme.typography.bodySmall, color = x.error)
        }
    }
}

@Composable
private fun ListenBrainzCard(
    state: ScrobbleState,
    busy: Boolean,
    error: ScrobbleError?,
    onSave: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val x = ShadeTheme.extras
    var token by rememberSaveable { mutableStateOf("") }
    SettingsCard {
        ServiceHeader(stringResource(R.string.settings_listenbrainz), state.listenbrainzConnected, state.listenbrainzUser, state.pendingListenbrainz)
        Spacer(Modifier.height(12.dp))
        if (state.listenbrainzConnected) {
            ShadeTextButton(stringResource(R.string.settings_disconnect), onDisconnect, destructive = true)
        } else {
            Text(stringResource(R.string.settings_listenbrainz_help), style = MaterialTheme.typography.bodySmall, color = x.text2)
            ShadeTextButton(stringResource(R.string.settings_listenbrainz_get_token), onOpenSettings)
            ShadeTextField(
                token, { token = it }, hint = stringResource(R.string.settings_listenbrainz_token), password = true, mono = true,
                clearable = false, containerColor = settingsFieldColors().first, borderColor = settingsFieldColors().second,
                focusHighlight = false,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(stringResource(R.string.settings_listenbrainz_save), { onSave(token) }, enabled = token.isNotBlank(), loading = busy)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(scrobbleErrorText(error), style = MaterialTheme.typography.bodySmall, color = x.error)
        }
    }
}

@Preview(widthDp = 412)
@Composable
private fun AccountCardPreview() {
    ShadeTubeThemeOf {
        Column(Modifier.background(ShadeTheme.extras.surface0).padding(vertical = 16.dp)) {
            AccountCard(SessionState.LoggedIn, UserProfile(id = "spotify:user:deniz", name = "Deniz", username = "deniz"), {}, {})
            Spacer(Modifier.height(12.dp))
            AccountCard(SessionState.LoggedOut, UserProfile(), {}, {})
        }
    }
}

/** For previews of sections that need a settings value. */
internal val PreviewSettings = Settings()
