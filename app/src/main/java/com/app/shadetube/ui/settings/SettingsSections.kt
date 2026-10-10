package com.app.shadetube.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.R
import com.app.shadetube.core.AudioQuality
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------- playback

@Composable
internal fun qualityLabel(q: AudioQuality): String =
    stringResource(if (q == AudioQuality.High) R.string.settings_quality_high else R.string.settings_quality_normal)

private val ALT_SOURCES = listOf("off", "piped", "invidious")

@Composable
internal fun PlaybackSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    val qualities = listOf(AudioQuality.High, AudioQuality.Normal)
    val altLabels = listOf(
        stringResource(R.string.settings_alt_off),
        stringResource(R.string.settings_alt_piped),
        stringResource(R.string.settings_alt_invidious),
    )
    SettingsScaffold(stringResource(R.string.settings_playback), contentPadding) {
        item(key = "g_quality") { SettingsGroupHeader(stringResource(R.string.settings_audio_quality)) }
        item(key = "quality") {
            SettingsRow(
                stringResource(R.string.settings_quality_wifi), icon = R.drawable.ic_wifi, value = qualityLabel(s.quality),
                onClick = { dialog = "quality" },
            )
        }
        item(key = "metered") {
            SettingsRow(
                stringResource(R.string.settings_quality_metered), icon = R.drawable.ic_speed, value = qualityLabel(s.meteredQuality),
                onClick = { dialog = "metered" },
            )
        }
        item(key = "g_play") { SettingsGroupHeader(stringResource(R.string.settings_playback)) }
        item(key = "preload") {
            SettingsSwitchRow(
                stringResource(R.string.settings_preload_next), s.preloadNext, { v -> vm.change { it.copy(preloadNext = v) } },
                icon = R.drawable.ic_next, subtitle = stringResource(R.string.settings_preload_next_sub),
            )
        }
        item(key = "sponsor") {
            SettingsSwitchRow(
                stringResource(R.string.settings_skip_sponsor), s.sponsorBlockEnabled, { v -> vm.change { it.copy(sponsorBlockEnabled = v) } },
                icon = R.drawable.ic_swap_source, subtitle = stringResource(R.string.settings_skip_sponsor_sub),
            )
        }
        item(key = "smart") {
            SettingsSwitchRow(
                stringResource(R.string.settings_smart_shuffle), s.smartShuffle, { v -> vm.change { it.copy(smartShuffle = v) } },
                icon = R.drawable.ic_shuffle_smart, subtitle = stringResource(R.string.settings_smart_shuffle_sub),
            )
        }
        item(key = "endless") {
            SettingsSwitchRow(
                stringResource(R.string.settings_autoplay_similar), s.endlessPlayback, { v -> vm.change { it.copy(endlessPlayback = v) } },
                icon = R.drawable.ic_radio,
            )
        }
        item(key = "g_alt") { SettingsGroupHeader(stringResource(R.string.settings_fallback_source)) }
        item(key = "alt") {
            val idx = ALT_SOURCES.indexOf(s.altSource).coerceAtLeast(0)
            SettingsRow(
                stringResource(R.string.settings_fallback_source), icon = R.drawable.ic_swap_source, value = altLabels[idx],
                onClick = { dialog = "alt" },
            )
        }
        item(key = "instance") {
            SettingsRow(
                stringResource(R.string.settings_alt_instance), icon = R.drawable.ic_link,
                subtitle = s.altSourceInstance.ifEmpty { stringResource(R.string.settings_alt_instance_builtin) },
                enabled = s.altSource != "off",
                onClick = { dialog = "instance" },
            )
        }
        item(key = "altnote") { SettingsNote(stringResource(R.string.settings_alt_note)) }
    }
    when (dialog) {
        "quality", "metered" -> {
            val metered = dialog == "metered"
            SettingsChoiceDialog(
                title = stringResource(if (metered) R.string.settings_quality_metered else R.string.settings_quality_wifi),
                options = qualities.map { qualityLabel(it) },
                subtitles = listOf(stringResource(R.string.settings_quality_high_sub), stringResource(R.string.settings_quality_normal_sub)),
                selectedIndex = qualities.indexOf(if (metered) s.meteredQuality else s.quality),
                onSelect = { i -> vm.change { if (metered) it.copy(meteredQuality = qualities[i]) else it.copy(quality = qualities[i]) } },
                onDismiss = { dialog = null },
            )
        }
        "alt" -> SettingsChoiceDialog(
            title = stringResource(R.string.settings_fallback_source),
            options = altLabels,
            selectedIndex = ALT_SOURCES.indexOf(s.altSource).coerceAtLeast(0),
            onSelect = { i -> vm.change { it.copy(altSource = ALT_SOURCES[i]) } },
            onDismiss = { dialog = null },
            footer = stringResource(R.string.settings_alt_note),
        )
        "instance" -> SettingsTextDialog(
            title = stringResource(R.string.settings_alt_instance),
            initial = s.altSourceInstance,
            body = stringResource(R.string.settings_alt_instance_body),
            hint = "https://",
            onConfirm = { v -> vm.change { it.copy(altSourceInstance = v) } },
            onDismiss = { dialog = null },
        )
    }
}

// ------------------------------------------------------------------------------------------------------------- sound

private val LOUDNESS_TARGETS = listOf(-19, -14, -11)

internal fun speedLabel(v: Float): String {
    val s = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    return "$s×"
}

@Composable
internal fun SoundSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(false) }
    // Sliders keep a local value while dragging and write the store when released.
    var crossfade by remember(s.crossfadeSec) { mutableFloatStateOf(s.crossfadeSec.toFloat()) }
    var music by remember(s.musicSpeed) { mutableFloatStateOf(s.musicSpeed) }
    var podcast by remember(s.podcastSpeed) { mutableFloatStateOf(s.podcastSpeed) }
    val targetLabels = listOf(
        stringResource(R.string.settings_loudness_quiet),
        stringResource(R.string.settings_loudness_normal),
        stringResource(R.string.settings_loudness_loud),
    )
    SettingsScaffold(stringResource(R.string.settings_sound), contentPadding) {
        item(key = "g_norm") { SettingsGroupHeader(stringResource(R.string.settings_normalize)) }
        item(key = "norm") {
            SettingsSwitchRow(
                stringResource(R.string.settings_normalize), s.normalizeVolume, { v -> vm.change { it.copy(normalizeVolume = v) } },
                icon = R.drawable.ic_volume_mid, subtitle = stringResource(R.string.settings_normalize_sub),
            )
        }
        item(key = "target") {
            val i = LOUDNESS_TARGETS.indexOf(s.loudnessTarget)
            SettingsRow(
                stringResource(R.string.settings_loudness_target), icon = R.drawable.ic_volume_high,
                value = if (i >= 0) targetLabels[i] else stringResource(R.string.settings_lufs, s.loudnessTarget),
                enabled = s.normalizeVolume, onClick = { dialog = true },
            )
        }
        item(key = "g_cross") { SettingsGroupHeader(stringResource(R.string.settings_transitions)) }
        item(key = "cross") {
            SettingsSliderRow(
                title = stringResource(R.string.settings_crossfade),
                value = crossfade,
                valueLabel = if (crossfade.roundToInt() == 0) stringResource(R.string.settings_crossfade_off)
                else stringResource(R.string.settings_crossfade_value, crossfade.roundToInt()),
                onValueChange = { crossfade = it },
                onValueChangeFinished = { vm.change { it.copy(crossfadeSec = crossfade.roundToInt().coerceIn(0, 12)) } },
                valueRange = 0f..12f, steps = 11,
                subtitle = stringResource(R.string.settings_crossfade_sub),
            )
        }
        item(key = "g_speed") { SettingsGroupHeader(stringResource(R.string.settings_speed)) }
        item(key = "music") {
            SettingsSliderRow(
                title = stringResource(R.string.settings_speed_music), value = music, valueLabel = speedLabel(music),
                onValueChange = { music = (it * 4).roundToInt() / 4f },
                onValueChangeFinished = { vm.change { it.copy(musicSpeed = music.coerceIn(0.5f, 3f)) } },
                valueRange = 0.5f..3f, steps = 9,
            )
        }
        item(key = "podcast") {
            SettingsSliderRow(
                title = stringResource(R.string.settings_speed_podcast), value = podcast, valueLabel = speedLabel(podcast),
                onValueChange = { podcast = (it * 4).roundToInt() / 4f },
                onValueChangeFinished = { vm.change { it.copy(podcastSpeed = podcast.coerceIn(0.5f, 3f)) } },
                valueRange = 0.5f..3f, steps = 9,
            )
        }
        item(key = "g_eq") { SettingsGroupHeader(stringResource(R.string.settings_equalizer)) }
        item(key = "eq") {
            SettingsRow(
                stringResource(R.string.settings_equalizer), icon = R.drawable.ic_equalizer,
                value = if (s.eqEnabled) eqPresetName(s.eqPreset) else stringResource(R.string.settings_off),
                chevron = true, onClick = { nav.navigate(Screen.Equalizer) },
            )
        }
    }
    if (dialog) {
        SettingsChoiceDialog(
            title = stringResource(R.string.settings_loudness_target),
            options = targetLabels,
            subtitles = LOUDNESS_TARGETS.map { stringResource(R.string.settings_lufs, it) },
            selectedIndex = LOUDNESS_TARGETS.indexOf(s.loudnessTarget),
            onSelect = { i -> vm.change { it.copy(loudnessTarget = LOUDNESS_TARGETS[i]) } },
            onDismiss = { dialog = false },
        )
    }
}

// --------------------------------------------------------------------------------------------------------- downloads

private val CAPS_GB = listOf(0, 1, 2, 5, 10, 20, 50, 100)

/** Download formats: MP3 bitrates (like the desktop) and 0 = the original stream. */
private val MP3_KBPS = listOf(320, 256, 192, 0)

@Composable
internal fun mp3KbpsLabel(kbps: Int): String =
    if (kbps <= 0) stringResource(R.string.settings_format_original) else stringResource(R.string.settings_mp3_kbps, kbps)

@Composable
internal fun capLabel(gb: Int): String =
    if (gb == 0) stringResource(R.string.settings_cap_none) else stringResource(R.string.settings_cap_gb, gb)

@Composable
internal fun DownloadsSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val caches by vm.caches.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var capDialog by remember { mutableStateOf(false) }
    var formatDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshCaches() }
    SettingsScaffold(stringResource(R.string.settings_download), contentPadding) {
        item(key = "manage") {
            SettingsRow(
                stringResource(R.string.downloads_title), icon = R.drawable.ic_downloaded,
                value = formatBytes(context, downloads.totalBytes), valueMono = true, chevron = true,
                onClick = { nav.navigate(Screen.Downloads) },
            )
        }
        item(key = "g_net") { SettingsGroupHeader(stringResource(R.string.settings_download)) }
        item(key = "wifi") {
            SettingsSwitchRow(
                stringResource(R.string.settings_wifi_only), s.downloadWifiOnly, { v -> vm.change { it.copy(downloadWifiOnly = v) } },
                icon = R.drawable.ic_wifi, subtitle = stringResource(R.string.settings_wifi_only_sub),
            )
        }
        item(key = "lyrics") {
            SettingsSwitchRow(
                stringResource(R.string.settings_embed_lyrics), s.lyricsInDownloads, { v -> vm.change { it.copy(lyricsInDownloads = v) } },
                icon = R.drawable.ic_lyrics, subtitle = stringResource(R.string.settings_embed_lyrics_sub),
            )
        }
        item(key = "folder") {
            SettingsRow(
                stringResource(R.string.settings_download_folder), icon = R.drawable.ic_folder,
                subtitle = caches.downloadsDir.ifEmpty { "…" },
            )
        }
        item(key = "format") {
            SettingsRow(
                stringResource(R.string.settings_mp3_quality), icon = R.drawable.ic_music_note,
                value = mp3KbpsLabel(s.downloadMp3Kbps), subtitle = stringResource(R.string.settings_mp3_quality_sub),
                onClick = { formatDialog = true },
            )
        }
        item(key = "g_sync") { SettingsGroupHeader(stringResource(R.string.settings_sync)) }
        item(key = "paused") {
            SettingsSwitchRow(
                stringResource(R.string.settings_sync_paused), s.syncPaused, { v -> vm.change { it.copy(syncPaused = v) } },
                icon = R.drawable.ic_pause, subtitle = stringResource(R.string.settings_sync_paused_sub),
            )
        }
        item(key = "metered") {
            SettingsSwitchRow(
                stringResource(R.string.settings_sync_metered), s.syncOnMetered, { v -> vm.change { it.copy(syncOnMetered = v) } },
                icon = R.drawable.ic_speed, subtitle = stringResource(R.string.settings_sync_metered_sub),
                enabled = !s.downloadWifiOnly,
            )
        }
        item(key = "dropped") {
            SettingsSwitchRow(
                stringResource(R.string.settings_sync_remove_dropped), s.syncRemoveDropped, { v -> vm.change { it.copy(syncRemoveDropped = v) } },
                icon = R.drawable.ic_trash, subtitle = stringResource(R.string.settings_sync_remove_dropped_sub),
            )
        }
        item(key = "cap") {
            SettingsRow(
                stringResource(R.string.settings_storage_limit), icon = R.drawable.ic_storage, value = capLabel(s.syncCapGb),
                subtitle = stringResource(R.string.settings_storage_limit_sub), onClick = { capDialog = true },
            )
        }
    }
    if (capDialog) {
        SettingsChoiceDialog(
            title = stringResource(R.string.settings_storage_limit),
            options = CAPS_GB.map { capLabel(it) },
            selectedIndex = CAPS_GB.indexOf(s.syncCapGb),
            onSelect = { i -> vm.change { it.copy(syncCapGb = CAPS_GB[i]) } },
            onDismiss = { capDialog = false },
        )
    }
    if (formatDialog) {
        SettingsChoiceDialog(
            title = stringResource(R.string.settings_mp3_quality),
            options = MP3_KBPS.map { mp3KbpsLabel(it) },
            selectedIndex = MP3_KBPS.indexOf(s.downloadMp3Kbps),
            onSelect = { i -> vm.change { it.copy(downloadMp3Kbps = MP3_KBPS[i]) } },
            onDismiss = { formatDialog = false },
        )
    }
}

// ------------------------------------------------------------------------------------------------------- local music

@Composable
internal fun LocalMusicSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_local_music), contentPadding) {
        item(key = "open") {
            SettingsRow(
                stringResource(R.string.local_title), icon = R.drawable.ic_folder,
                subtitle = pluralStringResource(R.plurals.settings_local_summary_songs, local.tracks.size, local.tracks.size) + " · " +
                    pluralStringResource(R.plurals.settings_local_summary_folders, s.localFolders.size, s.localFolders.size),
                chevron = true, onClick = { nav.navigate(Screen.LocalFiles) },
            )
        }
        item(key = "scan") {
            SettingsRow(
                stringResource(R.string.settings_local_rescan), icon = R.drawable.ic_refresh,
                subtitle = if (local.scanning) stringResource(R.string.local_scanning) else null,
                enabled = !local.scanning && s.localFolders.isNotEmpty(),
                onClick = vm::rescanLocal,
            )
        }
        item(key = "note") { SettingsNote(stringResource(R.string.settings_local_note)) }
    }
}

// ----------------------------------------------------------------------------------------------------------- storage

@Composable
internal fun StorageSection(vm: SettingsViewModel, contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val caches by vm.caches.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<CacheKind?>(null) }
    LaunchedEffect(Unit) { vm.refreshCaches() }
    fun size(b: Long) = if (b < 0) "…" else formatBytes(context, b)
    SettingsScaffold(stringResource(R.string.settings_storage), contentPadding) {
        item(key = "downloads") {
            SettingsRow(
                stringResource(R.string.downloads_title), icon = R.drawable.ic_downloaded,
                value = formatBytes(context, downloads.totalBytes), valueMono = true, chevron = true,
                onClick = { nav.navigate(Screen.Downloads) },
            )
        }
        item(key = "g_cache") { SettingsGroupHeader(stringResource(R.string.settings_clear_cache)) }
        val rows = listOf(
            Triple(CacheKind.Images, R.string.settings_cache_images, caches.images),
            Triple(CacheKind.Matches, R.string.settings_cache_matches, caches.matches),
            Triple(CacheKind.Lyrics, R.string.settings_cache_lyrics, caches.lyrics),
            Triple(CacheKind.MusicBrainz, R.string.settings_cache_musicbrainz, caches.musicBrainz),
        )
        rows.forEach { (kind, label, bytes) ->
            item(key = kind.name) {
                SettingsRow(
                    stringResource(label),
                    icon = when (kind) {
                        CacheKind.Images -> R.drawable.ic_grid
                        CacheKind.Matches -> R.drawable.ic_swap_source
                        CacheKind.Lyrics -> R.drawable.ic_lyrics
                        else -> R.drawable.ic_storage
                    },
                    subtitle = size(bytes),
                    trailing = { ShadeTextButton(stringResource(R.string.settings_clear), { confirm = kind }, enabled = bytes > 0) },
                )
            }
        }
        item(key = "note") { SettingsNote(stringResource(R.string.settings_cache_note)) }
    }
    confirm?.let { kind ->
        ShadeDialog(
            onDismissRequest = { confirm = null },
            title = stringResource(R.string.settings_clear_confirm_title),
            text = stringResource(
                when (kind) {
                    CacheKind.Matches -> R.string.settings_clear_matches_body
                    CacheKind.CrashLogs -> R.string.settings_clear_crashes_body
                    else -> R.string.settings_clear_confirm_body
                },
            ),
            confirmLabel = stringResource(R.string.settings_clear),
            onConfirm = { confirm = null; vm.clearCache(kind) },
            destructive = true,
        )
    }
}

/** Shared column for small radio groups inside a LazyColumn item. */
@Composable
internal fun RadioGroup(options: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, enabled: List<Boolean> = emptyList()) {
    Column(Modifier.selectableGroup()) {
        options.forEachIndexed { i, (label, sub) ->
            SettingsRadioRow(label, i == selected, { onSelect(i) }, subtitle = sub, enabled = enabled.getOrElse(i) { true })
        }
    }
}
