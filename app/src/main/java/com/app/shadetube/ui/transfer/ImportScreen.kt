package com.app.shadetube.ui.transfer

import androidx.compose.ui.text.input.KeyboardType
import com.app.shadetube.ui.components.StepDots
import com.app.shadetube.ui.components.ShadeTextField
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.catalog.isLocalId
import com.app.shadetube.library.CollectionKind
import com.app.shadetube.playlistio.ImportPreview
import com.app.shadetube.playlistio.ImportProblem
import com.app.shadetube.playlistio.SpotifyOutcome
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeSwitch
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.SourceBadge
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/** What the SAF picker offers (playlist files; text / octet-stream because many providers type .m3u that way). */
private val PLAYLIST_MIME_TYPES = arrayOf(
    "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl",
    "text/csv", "text/comma-separated-values", "application/xspf+xml", "application/json",
    "text/plain", "text/*", "application/octet-stream",
)

/** Playlist import (SCREENS 23). */
@Composable
fun ImportScreen(contentPadding: PaddingValues, pendingFromIntent: Boolean, youtubeUrl: String) {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    val vm: ImportViewModel = viewModel(key = "import:$pendingFromIntent:$youtubeUrl") { ImportViewModel(pendingFromIntent, youtubeUrl) }
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickFile(uri) }

    BackHandler(enabled = state.step > 0 || state.loading) {
        if (state.loading) vm.cancelLoading() else vm.back()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShadeTopBar(
            onNavigate = { if (!vm.back()) nav.back() },
            title = stringResource(R.string.import_title),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepDots(state.step, 3)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when (state.step) {
                        0 -> R.string.transfer_step_source
                        1 -> R.string.import_preview
                        else -> R.string.transfer_step_create
                    },
                ),
                style = ShadeMono.overline, color = ShadeTheme.extras.text3,
            )
            Spacer(Modifier.height(12.dp))
        }
        val enter = ShadeMotion.fadeEnter()
        val exit = ShadeMotion.fadeExit()
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { enter togetherWith exit },
            modifier = Modifier.weight(1f),
            label = "importStep",
        ) { step ->
            when (step) {
                0 -> SourceStep(
                    state = state,
                    onPick = { picker.launch(PLAYLIST_MIME_TYPES) },
                    onLink = vm::setLink,
                    onPreviewLink = vm::previewLink,
                    onCancel = vm::cancelLoading,
                )
                1 -> state.preview?.let { PreviewStep(it, contentPadding, onNext = vm::toName) }
                else -> state.preview?.let { p ->
                    NameStep(
                        preview = p,
                        name = state.name,
                        canSpotify = state.canSpotify,
                        alsoOnSpotify = state.alsoOnSpotify,
                        committing = state.committing,
                        contentPadding = contentPadding,
                        onName = vm::setName,
                        onSpotify = vm::setAlsoOnSpotify,
                        onCreate = {
                            vm.commit(
                                onDone = { r, name ->
                                    val listName = name.ifEmpty { res.getString(R.string.transfer_default_name) }
                                    var text = res.getQuantityString(R.plurals.transfer_import_done, r.added, listName, r.added)
                                    when (val o = r.spotify) {
                                        is SpotifyOutcome.Created -> text += " · " + res.getString(R.string.transfer_spotify_created)
                                        is SpotifyOutcome.Partial -> text += " · " + res.getQuantityString(R.plurals.transfer_spotify_partial, o.added, o.added)
                                        SpotifyOutcome.Failed -> text += " · " + res.getString(R.string.transfer_spotify_failed)
                                        SpotifyOutcome.Skipped -> Unit
                                    }
                                    nav.message(text)
                                    nav.back()
                                    nav.navigate(Screen.Collection(CollectionKind.Playlist, r.playlistId))
                                },
                                onFailed = { nav.message(res.getString(R.string.transfer_import_failed)) },
                            )
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- step 1

@Composable
private fun SourceStep(
    state: ImportViewModel.State,
    onPick: () -> Unit,
    onLink: (String) -> Unit,
    onPreviewLink: () -> Unit,
    onCancel: () -> Unit,
) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    if (state.loading) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(40.dp), color = accent.accent, trackColor = accent.progressTrack, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.transfer_reading), style = MaterialTheme.typography.titleMedium, color = x.text1)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.transfer_reading_body), style = MaterialTheme.typography.bodyMedium, color = x.text2)
            Spacer(Modifier.height(16.dp))
            ShadeTextButton(stringResource(R.string.action_cancel), onCancel, color = x.text2)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(x.surface2)
                .clickable(role = Role.Button, onClick = onPick)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(accent.container),
                contentAlignment = Alignment.Center,
            ) { ShadeIcon(R.drawable.ic_folder, null, tint = accent.accent) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.import_pick_file), style = MaterialTheme.typography.titleMedium, color = x.text1)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.transfer_pick_formats), style = MaterialTheme.typography.bodySmall, color = x.text2)
            }
            ShadeIcon(R.drawable.ic_chevron_right, null, tint = x.text3, size = 20.dp)
        }
        if (state.error == ImportError.Unreadable) {
            Spacer(Modifier.height(8.dp))
            ErrorLine(stringResource(R.string.transfer_error_unreadable))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = x.surface3)
            Text(
                stringResource(R.string.transfer_or), Modifier.padding(horizontal = 12.dp),
                style = ShadeMono.overline, color = x.text3,
            )
            HorizontalDivider(Modifier.weight(1f), color = x.surface3)
        }
        Text(stringResource(R.string.import_from_link), style = MaterialTheme.typography.titleSmall, color = x.text1)
        Spacer(Modifier.height(8.dp))
        ShadeTextField(
            value = state.link,
            onValueChange = onLink,
            hint = stringResource(R.string.transfer_link_hint),
            leadingIcon = R.drawable.ic_link,
            keyboardType = KeyboardType.Uri,
            onDone = { if (state.link.isNotBlank()) onPreviewLink() },
        )
        when (state.error) {
            ImportError.NotYouTubeList -> {
                Spacer(Modifier.height(8.dp))
                ErrorLine(stringResource(R.string.transfer_error_not_youtube))
            }
            ImportError.YouTubeFailed -> {
                Spacer(Modifier.height(8.dp))
                ErrorLine(stringResource(R.string.transfer_error_youtube))
            }
            else -> Unit
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            stringResource(R.string.import_preview), onPreviewLink, Modifier.fillMaxWidth(),
            enabled = state.link.isNotBlank(), icon = R.drawable.ic_import,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ErrorLine(text: String) {
    val x = ShadeTheme.extras
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShadeIcon(R.drawable.ic_error, null, tint = x.error, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = x.error)
    }
}

// ---------------------------------------------------------------------------------------------------- step 2

@Composable
private fun PreviewStep(preview: ImportPreview, contentPadding: PaddingValues, onNext: () -> Unit) {
    var problemsOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item("summary") { SummaryCard(preview) }
            if (preview.unresolved.isNotEmpty()) {
                item("problems-h") { ProblemsHeader(preview.unresolved.size, problemsOpen) { problemsOpen = !problemsOpen } }
                if (problemsOpen) {
                    items(preview.shownProblems, key = { "p:" + it.line + ":" + it.text }) { ProblemRow(it) }
                    val rest = preview.unresolved.size - preview.shownProblems.size
                    if (rest > 0) {
                        item("problems-more") {
                            Text(
                                pluralStringResource(R.plurals.transfer_more_rows, rest, rest),
                                Modifier.padding(start = 56.dp, end = 20.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = ShadeTheme.extras.text3,
                            )
                        }
                    }
                }
            }
            itemsIndexed(preview.tracks, key = { i, t -> "$i:${t.id}" }) { _, t ->
                TrackRow(
                    track = t,
                    contentPadding = PaddingValues(start = 20.dp, end = 0.dp),
                    trailing = {
                        SourceBadge(matchLabel(t))
                        Spacer(Modifier.width(20.dp))
                    },
                )
            }
        }
        BottomAction(contentPadding) {
            PrimaryButton(
                stringResource(R.string.transfer_next), onNext, Modifier.fillMaxWidth(),
                enabled = preview.tracks.isNotEmpty(),
            )
        }
    }
}

/** Found / unreadable counts in mono 18 + where the songs come from. */
@Composable
private fun SummaryCard(preview: ImportPreview) {
    val x = ShadeTheme.extras
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(x.surface2)
            .padding(16.dp),
    ) {
        if (preview.name.isNotEmpty()) {
            Text(preview.name, style = MaterialTheme.typography.titleMedium, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
        }
        Row {
            Stat(preview.tracks.size, stringResource(R.string.transfer_found_label), Modifier.weight(1f))
            Stat(preview.unresolved.size, stringResource(R.string.transfer_unreadable_label), Modifier.weight(1f), warn = preview.unresolved.isNotEmpty())
        }
        val parts = buildList {
            if (preview.spotifyCount > 0) add("Spotify ${preview.spotifyCount}")
            if (preview.localCount > 0) add(stringResource(R.string.transfer_badge_local) + " ${preview.localCount}")
            if (preview.youtubeCount > 0) add("YouTube ${preview.youtubeCount}")
            val other = preview.tracks.size - preview.spotifyCount - preview.localCount - preview.youtubeCount
            if (other > 0) add(stringResource(R.string.transfer_badge_by_name) + " $other")
        }
        if (parts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(parts.joinToString(" · "), style = ShadeMono.small, color = x.text3)
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    val x = ShadeTheme.extras
    Column(modifier) {
        Text(value.toString(), style = ShadeMono.large, color = if (warn) x.warning else x.text1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = x.text2)
    }
}

@Composable
private fun ProblemsHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    val x = ShadeTheme.extras
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadeIcon(R.drawable.ic_warning, null, tint = x.warning)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.import_unreadable, count), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge, color = x.text1,
        )
        ShadeIcon(if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, null, tint = x.text2, size = 20.dp)
    }
}

@Composable
private fun ProblemRow(p: ImportProblem) {
    val x = ShadeTheme.extras
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.transfer_line, p.line), style = ShadeMono.small, color = x.text3)
        Spacer(Modifier.width(8.dp))
        Text(p.text, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Where a found song plays from: Spotify id, local file, exactly that YouTube video, or matched by its name. */
@Composable
private fun matchLabel(t: Track): String = when {
    t.id.startsWith("spotify:track:") -> "Spotify"
    isLocalId(t.id) -> stringResource(R.string.transfer_badge_local)
    t.id.startsWith("yt:") -> "YouTube"
    else -> stringResource(R.string.transfer_badge_by_name)
}

// ---------------------------------------------------------------------------------------------------- step 3

@Composable
private fun NameStep(
    preview: ImportPreview,
    name: String,
    canSpotify: Boolean,
    alsoOnSpotify: Boolean,
    committing: Boolean,
    contentPadding: PaddingValues,
    onName: (String) -> Unit,
    onSpotify: (Boolean) -> Unit,
    onCreate: () -> Unit,
) {
    val x = ShadeTheme.extras
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.transfer_name_label), style = MaterialTheme.typography.titleSmall, color = x.text1)
            Spacer(Modifier.height(8.dp))
            ShadeTextField(
                value = name,
                onValueChange = onName,
                hint = stringResource(R.string.transfer_default_name),
                leadingIcon = R.drawable.ic_playlist,
                enabled = !committing,
                onDone = { if (!committing) onCreate() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(R.plurals.transfer_create_summary, preview.tracks.size, preview.tracks.size),
                style = MaterialTheme.typography.bodySmall, color = x.text2,
            )
            if (canSpotify) {
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(x.surface2)
                        .clickable(enabled = !committing, role = Role.Switch) { onSpotify(!alsoOnSpotify) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.transfer_also_spotify), style = MaterialTheme.typography.bodyLarge, color = x.text1)
                        Text(
                            pluralStringResource(R.plurals.transfer_also_spotify_body, preview.spotifyCount, preview.spotifyCount),
                            style = MaterialTheme.typography.bodySmall, color = x.text2,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ShadeSwitch(alsoOnSpotify, null, enabled = !committing)
                }
            }
        }
        BottomAction(contentPadding) {
            PrimaryButton(
                stringResource(R.string.import_create), onCreate, Modifier.fillMaxWidth(),
                icon = R.drawable.ic_plus, loading = committing,
            )
        }
    }
}

@Composable
private fun BottomAction(contentPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        content()
    }
}

@Preview(backgroundColor = 0xFF0B0C10, showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun PreviewStepPreview() {
    val tracks = listOf(
        Track(id = "spotify:track:1", name = "Gülümse", artists = listOf(com.app.shadetube.catalog.ArtistRef("", "Sezen Aksu"))),
        Track(id = "yt:abc", name = "Gece", artists = listOf(com.app.shadetube.catalog.ArtistRef("", "Kanal"))),
        Track(id = "local:1", name = "Demo", artists = listOf(com.app.shadetube.catalog.ArtistRef("", "Ben"))),
    )
    ShadeTubeThemeOf {
        PreviewStep(
            ImportPreview("Yol listesi", tracks, listOf(ImportProblem(12, "???"), ImportProblem(40, "bozuk satır"))),
            PaddingValues(), onNext = {},
        )
    }
}
