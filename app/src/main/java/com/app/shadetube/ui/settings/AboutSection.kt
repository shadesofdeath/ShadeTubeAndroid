package com.app.shadetube.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.R
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeLogo
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.SettingsSection
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.update.UpdateError
import com.app.shadetube.update.UpdateState

internal const val GITHUB_URL = "https://github.com/shadesofdeath/ShadeTubeAndroid"

@Composable
internal fun AboutSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val caches by vm.caches.collectAsStateWithLifecycle()
    var awaitingPermission by remember { mutableStateOf(false) }
    var confirmCrashes by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshCaches() }
    // Back from "install unknown apps": continue the update once it is allowed.
    LifecycleResumeEffect(awaitingPermission) {
        if (awaitingPermission && vm.canInstall()) {
            awaitingPermission = false
            vm.downloadUpdate()
        }
        onPauseOrDispose { }
    }
    SettingsScaffold(stringResource(R.string.settings_about), contentPadding) {
        item(key = "logo") {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ShadeLogo(height = 44.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_version, vm.currentVersion), style = ShadeMono.medium, color = ShadeTheme.extras.text2)
            }
        }
        item(key = "update") {
            UpdateCard(
                state = update,
                current = vm.currentVersion,
                onCheck = vm::checkUpdate,
                onDownload = vm::downloadUpdate,
                onCancel = vm::cancelUpdate,
                onSkip = vm::skipUpdate,
                onInstall = {
                    if (vm.canInstall()) vm.installUpdate() else {
                        awaitingPermission = true
                        runCatching { context.startActivity(vm.installPermissionIntent()) }
                    }
                },
                onAllowInstall = {
                    awaitingPermission = true
                    try {
                        context.startActivity(vm.installPermissionIntent())
                    } catch (_: ActivityNotFoundException) {
                    }
                },
                onConfirmIntent = { intent -> runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onOpenRelease = { url -> openUrl(context, url.ifEmpty { "$GITHUB_URL/releases" }) },
            )
        }
        item(key = "auto") {
            SettingsSwitchRow(
                stringResource(R.string.settings_update_auto), s.updateCheck, { v -> vm.change { it.copy(updateCheck = v) } },
                icon = R.drawable.ic_refresh, subtitle = stringResource(R.string.settings_update_auto_sub),
            )
        }
        item(key = "g_links") { SettingsGroupHeader(stringResource(R.string.settings_group_project)) }
        item(key = "github") {
            SettingsRow(
                stringResource(R.string.settings_github), icon = R.drawable.ic_external_link,
                subtitle = GITHUB_URL.removePrefix("https://"), onClick = { openUrl(context, GITHUB_URL) },
            )
        }
        item(key = "licenses") {
            SettingsRow(
                stringResource(R.string.settings_licenses), icon = R.drawable.ic_info, chevron = true,
                onClick = { nav.navigate(Screen.Settings(SettingsSection.Licenses)) },
            )
        }
        item(key = "crashes") {
            SettingsRow(
                stringResource(R.string.settings_crash_logs), icon = R.drawable.ic_warning,
                subtitle = stringResource(R.string.settings_crash_logs_sub),
                value = caches.crashLogs.toString(), valueMono = true,
                trailing = if (caches.crashLogs > 0) ({ ShadeTextButton(stringResource(R.string.settings_clear), { confirmCrashes = true }) }) else null,
            )
        }
        item(key = "fonts") { SettingsNote(stringResource(R.string.fonts_license)) }
    }
    if (confirmCrashes) {
        ShadeDialog(
            onDismissRequest = { confirmCrashes = false },
            title = stringResource(R.string.settings_clear_confirm_title),
            text = stringResource(R.string.settings_clear_crashes_body),
            confirmLabel = stringResource(R.string.settings_clear),
            onConfirm = { confirmCrashes = false; vm.clearCache(CacheKind.CrashLogs) },
            destructive = true,
        )
    }
}

@Composable
private fun updateErrorText(e: UpdateError, detail: String): String = when (e) {
    UpdateError.Network -> stringResource(R.string.settings_update_err_network)
    UpdateError.RateLimited -> stringResource(R.string.settings_update_err_rate)
    UpdateError.HttpStatus -> stringResource(R.string.settings_update_err_http, detail)
    UpdateError.BadRelease -> stringResource(R.string.settings_update_err_bad_release)
    UpdateError.NoPackage -> stringResource(R.string.settings_update_err_no_package)
    UpdateError.Download -> stringResource(R.string.settings_update_err_download)
    UpdateError.Io -> stringResource(R.string.settings_update_err_io)
    UpdateError.AssetDigestMismatch, UpdateError.NotesDigestMismatch -> stringResource(R.string.settings_update_err_digest)
    UpdateError.NotAnApk, UpdateError.PackageMismatch, UpdateError.VersionMismatch -> stringResource(R.string.settings_update_err_apk)
    UpdateError.SignatureMismatch -> stringResource(R.string.settings_update_err_signature)
    UpdateError.InstallPermission -> stringResource(R.string.settings_update_err_permission)
    UpdateError.InstallFailed -> stringResource(R.string.settings_update_err_install, detail)
    UpdateError.Cancelled -> stringResource(R.string.settings_update_cancelled)
}

/** Ayarlar › Hakkında update card: every [UpdateState] with its actions. */
@Composable
internal fun UpdateCard(
    state: UpdateState,
    current: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstall: () -> Unit,
    onConfirmIntent: (android.content.Intent) -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val x = ShadeTheme.extras
    val context = LocalContext.current
    SettingsCard {
        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleMedium, color = x.text1)
        Spacer(Modifier.height(4.dp))
        @Composable
        fun line(text: String, color: Color = x.text2) =
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

        @Composable
        fun busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = ShadeTheme.accent.accent, strokeWidth = 2.dp, trackColor = Color.Transparent)
            Spacer(Modifier.width(12.dp))
            line(text)
        }
        when (state) {
            UpdateState.Idle -> {
                line(stringResource(R.string.settings_update_idle, current))
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.settings_check_update), onCheck, icon = R.drawable.ic_refresh)
            }
            UpdateState.Checking -> busy(stringResource(R.string.settings_update_checking))
            is UpdateState.UpToDate -> {
                line(stringResource(R.string.settings_update_up_to_date, state.current), ShadeTheme.accent.accent)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.settings_check_update), onCheck, icon = R.drawable.ic_refresh)
            }
            UpdateState.NoReleases -> {
                line(stringResource(R.string.settings_update_no_releases))
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.settings_check_update), onCheck, icon = R.drawable.ic_refresh)
            }
            is UpdateState.Available -> {
                line(stringResource(R.string.settings_update_available, state.version), x.text1)
                if (state.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        state.notes.trim(), style = MaterialTheme.typography.bodySmall, color = x.text2,
                        maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                    )
                    ShadeTextButton(stringResource(if (expanded) R.string.settings_less else R.string.settings_more), { expanded = !expanded })
                }
                Spacer(Modifier.height(12.dp))
                if (state.asset != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(stringResource(R.string.settings_update_install), onDownload, icon = R.drawable.ic_download)
                        ShadeTextButton(stringResource(R.string.settings_update_skip), onSkip, color = x.text2)
                    }
                    if (state.asset.size > 0) {
                        Text(formatBytes(context, state.asset.size), style = ShadeMono.small, color = x.text3)
                    }
                } else {
                    line(stringResource(R.string.settings_update_err_no_package))
                    ShadeTextButton(stringResource(R.string.settings_update_open_release), { onOpenRelease(state.release.htmlUrl) })
                }
            }
            is UpdateState.Downloading -> {
                line(stringResource(R.string.settings_update_downloading, state.release.version))
                Spacer(Modifier.height(12.dp))
                LinearProgress(if (state.total > 0) state.fraction else null, height = 4.dp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.total > 0) "${formatBytes(context, state.done)} / ${formatBytes(context, state.total)}" else formatBytes(context, state.done),
                        Modifier.weight(1f), style = ShadeMono.small, color = x.text2,
                    )
                    ShadeTextButton(stringResource(R.string.action_cancel), onCancel, color = x.text2)
                }
            }
            is UpdateState.Verifying -> busy(stringResource(R.string.settings_update_verifying))
            is UpdateState.ReadyToInstall -> {
                line(stringResource(R.string.settings_update_ready, state.release.version), x.text1)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.settings_update_install_now), onInstall)
            }
            is UpdateState.Installing -> busy(stringResource(R.string.settings_update_installing))
            is UpdateState.NeedsUserAction -> {
                line(stringResource(R.string.settings_update_confirm), x.text1)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.settings_update_confirm_button), { onConfirmIntent(state.intent) })
            }
            is UpdateState.Error -> {
                line(updateErrorText(state.error, state.detail), x.error)
                Spacer(Modifier.height(12.dp))
                when {
                    state.error == UpdateError.InstallPermission ->
                        PrimaryButton(stringResource(R.string.settings_update_allow), onAllowInstall, icon = R.drawable.ic_lock)
                    state.release != null ->
                        SecondaryButton(stringResource(R.string.action_retry), onDownload, icon = R.drawable.ic_refresh)
                    else -> SecondaryButton(stringResource(R.string.action_retry), onCheck, icon = R.drawable.ic_refresh)
                }
            }
        }
    }
}

private data class OssEntry(val name: String, val license: String)

private val OPEN_SOURCE = listOf(
    OssEntry("Jetpack Compose · AndroidX", "Apache License 2.0"),
    OssEntry("Media3 (ExoPlayer, MediaSession)", "Apache License 2.0"),
    OssEntry("WorkManager", "Apache License 2.0"),
    OssEntry("Kotlin · kotlinx.coroutines · kotlinx.serialization", "Apache License 2.0"),
    OssEntry("OkHttp · Okio", "Apache License 2.0"),
    OssEntry("Coil", "Apache License 2.0"),
    // Kotlin port of shadesofdeath/YoutubeExplode (C++, by the ShadeTube author), which is modelled on the C# library
    // Tyrrrz/YoutubeExplode (LGPL-3.0) — same wording as the desktop THIRD_PARTY_NOTICES.md.
    OssEntry("YoutubeExplode (Kotlin port of shadesofdeath/YoutubeExplode)", "modelled on Tyrrrz/YoutubeExplode (LGPL-3.0)"),
    OssEntry("Bricolage Grotesque · Instrument Sans · JetBrains Mono", "SIL Open Font License 1.1"),
    OssEntry("MusicBrainz · Cover Art Archive · ListenBrainz", "CC0 / CC BY-NC-SA (data)"),
    OssEntry("LRCLIB", "lyrics data"),
    OssEntry("SponsorBlock", "CC BY-NC-SA 4.0 (data)"),
    OssEntry("Radio Browser", "public domain (data)"),
)

@Composable
internal fun LicensesSection(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val ofl = remember {
        runCatching { context.resources.openRawResource(R.raw.ofl).bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    val x = ShadeTheme.extras
    SettingsScaffold(stringResource(R.string.settings_licenses), contentPadding) {
        item(key = "g_oss") { SettingsGroupHeader(stringResource(R.string.settings_open_source)) }
        OPEN_SOURCE.forEach { e ->
            item(key = e.name) { SettingsRow(e.name, subtitle = e.license) }
        }
        item(key = "g_ofl") { SettingsGroupHeader(stringResource(R.string.settings_fonts_license_title)) }
        item(key = "ofl") {
            SettingsCard {
                Text(ofl, style = ShadeMono.small, color = x.text2)
            }
        }
    }
}
