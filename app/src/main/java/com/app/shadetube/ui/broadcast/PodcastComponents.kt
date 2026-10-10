package com.app.shadetube.ui.broadcast

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import com.app.shadetube.ui.components.ShadeTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.shadetube.R
import com.app.shadetube.podcasts.DirectoryEntry
import com.app.shadetube.podcasts.Episode
import com.app.shadetube.podcasts.PodcastErrorKind
import com.app.shadetube.podcasts.PodcastEvent
import com.app.shadetube.podcasts.Podcasts
import com.app.shadetube.podcasts.Subscription
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.CountBadge
import com.app.shadetube.ui.components.DownloadIndicator
import com.app.shadetube.ui.components.DownloadUiState
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.NowPlayingBars
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeDialog
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf

/** Subscription tile (3 columns): 108 cover with the new-episode count badge, title 12.5 one line. */
@Composable
internal fun SubscriptionTile(sub: Subscription, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 108.dp) {
    val x = ShadeTheme.extras
    val newCd = if (sub.newEpisodes > 0) stringResource(R.string.podcast_new_episodes, sub.newEpisodes) else null
    Column(
        modifier
            .clip(ShadeRadius.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (newCd != null) contentDescription = "${sub.title}, $newCd" },
    ) {
        Box {
            Artwork(sub.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = size)
            if (sub.newEpisodes > 0) CountBadge(sub.newEpisodes, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(sub.title, Modifier.width(size), style = ShadeTextStyles.tileTitle, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A directory entry (search result) row: cover 56 · title + author. */
@Composable
internal fun DirectoryRow(entry: DirectoryEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(entry.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(entry.author, entry.genre).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Episode row (88, SCREENS 17): date mono 11 text3 · title 2 lines (accent when playing) · remaining time + 3dp
 * progress (or "Oynatıldı") · download 40 (ring while downloading, check when done). Tap plays, long-press opens the
 * menu. [showCover] for lists that mix shows (new episodes).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeRow(
    ui: EpisodeUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = false,
) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val e = ui.episode
    val locale = LocalConfiguration.current.locales[0]
    val menuLabel = stringResource(R.string.common_more_cd)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = menuLabel)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            Box(contentAlignment = Alignment.Center) {
                Artwork(e.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = 56.dp)
                if (ui.current) {
                    Box(Modifier.size(56.dp).clip(ShadeRadius.cover).background(x.scrim.copy(alpha = 0.5f)))
                    NowPlayingBars(ui.playing)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            val date = formatEpisodeDate(e.published, locale)
            val head = if (showCover && e.show.isNotEmpty()) listOf(date, e.show).filter { it.isNotEmpty() }.joinToString(" · ") else date
            if (head.isNotEmpty()) {
                Text(head.uppercase(locale), style = ShadeMono.small, color = x.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.current && !showCover) {
                    NowPlayingBars(ui.playing)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    e.title, style = MaterialTheme.typography.titleSmall,
                    color = when { ui.current -> accent.accent; ui.played -> x.text2; else -> x.text1 },
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            EpisodeProgressLine(ui)
        }
        Spacer(Modifier.width(4.dp))
        EpisodeDownloadButton(ui.download, onDownload)
    }
}

/** "12 dk kaldı" + 3dp progress, "Oynatıldı" with a check, or the duration. */
@Composable
internal fun EpisodeProgressLine(ui: EpisodeUi, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when {
            ui.played -> {
                ShadeIcon(R.drawable.ic_check, null, tint = ShadeTheme.accent.accent, size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.broadcast_podcast_played), style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1)
            }
            ui.inProgress && ui.durationMs > 0 -> {
                Text(
                    stringResource(R.string.podcast_remaining, minutesLeft(ui.positionMs, ui.durationMs)),
                    style = ShadeMono.small, color = x.text2, maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                LinearProgress(ui.progress, Modifier.width(64.dp), height = 3.dp)
            }
            ui.durationMs > 0 -> Text(
                stringResource(R.string.broadcast_podcast_minutes, minutesLeft(0, ui.durationMs)),
                style = ShadeMono.small, color = x.text2, maxLines = 1,
            )
        }
    }
}

/** Download button 40 (48 hit): download icon, ring + stop while downloading, accent check when done. */
@Composable
internal fun EpisodeDownloadButton(state: DownloadUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    val cd = stringResource(
        when (state) {
            DownloadUiState.Done -> R.string.broadcast_podcast_delete_download
            DownloadUiState.Queued, is DownloadUiState.Downloading -> R.string.broadcast_podcast_cancel_download
            else -> R.string.broadcast_podcast_download
        },
    )
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = cd, onClick = onClick)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        if (state == DownloadUiState.None || state == DownloadUiState.Failed) {
            ShadeIcon(R.drawable.ic_download, null, tint = x.text2)
        } else {
            DownloadIndicator(state)
        }
    }
}

@Composable
internal fun EpisodeRowSkeleton(showCover: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            SkeletonBox(Modifier.size(56.dp), ShadeRadius.cover)
            Spacer(Modifier.width(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(72.dp, height = 10.dp)
            SkeletonLine(220.dp)
            SkeletonLine(96.dp, height = 10.dp)
        }
    }
}

/** Long-press sheet of an episode: details, played / unplayed, download / delete. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeMenuSheet(
    ui: EpisodeUi,
    onDismiss: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDetails: (() -> Unit)?,
    onShow: (() -> Unit)? = null,
) {
    ShadeBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(ui.episode.title, subtitle = ui.episode.show.ifEmpty { null })
        if (onDetails != null) {
            SheetMenuRow(R.drawable.ic_info, stringResource(R.string.broadcast_podcast_details), { onDismiss(); onDetails() })
        }
        if (onShow != null) {
            SheetMenuRow(R.drawable.ic_podcast, stringResource(R.string.broadcast_podcast_go_to_show), { onDismiss(); onShow() })
        }
        if (ui.played) {
            SheetMenuRow(R.drawable.ic_refresh, stringResource(R.string.podcast_mark_unplayed), { onDismiss(); onSetPlayed(false) })
        } else {
            SheetMenuRow(R.drawable.ic_check, stringResource(R.string.podcast_mark_played), { onDismiss(); onSetPlayed(true) })
        }
        when (ui.download) {
            DownloadUiState.Done -> SheetMenuRow(
                R.drawable.ic_trash, stringResource(R.string.broadcast_podcast_delete_download), { onDismiss(); onDelete() }, destructive = true,
            )
            DownloadUiState.Queued, is DownloadUiState.Downloading -> SheetMenuRow(
                R.drawable.ic_close, stringResource(R.string.broadcast_podcast_cancel_download), { onDismiss(); onDownload() },
            )
            else -> SheetMenuRow(R.drawable.ic_download, stringResource(R.string.broadcast_podcast_download), { onDismiss(); onDownload() })
        }
    }
}

@Composable
internal fun DeleteDownloadDialog(episode: Episode, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ShadeDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.broadcast_podcast_delete_title),
        text = stringResource(R.string.broadcast_podcast_delete_body, episode.title),
        confirmLabel = stringResource(R.string.action_delete),
        onConfirm = { onDismiss(); onConfirm() },
        destructive = true,
    )
}

/**
 * Episode rows' interaction state of a screen: the long-press sheet and the delete confirmation. [content] gets the
 * handlers to pass to each row.
 */
@Composable
internal fun EpisodeInteractions(
    vm: EpisodeActionsViewModel,
    showDetails: Boolean = true,
    showShowLink: Boolean = false,
    content: @Composable (onLongClick: (EpisodeUi) -> Unit, onDownload: (EpisodeUi) -> Unit) -> Unit,
) {
    val nav = LocalNavigator.current
    var menuFor by remember { mutableStateOf<EpisodeUi?>(null) }
    var deleteFor by remember { mutableStateOf<Episode?>(null) }
    val onDownload: (EpisodeUi) -> Unit = { ui -> if (ui.download == DownloadUiState.Done) deleteFor = ui.episode else vm.toggleDownload(ui) }
    content({ menuFor = it }, onDownload)
    menuFor?.let { ui ->
        EpisodeMenuSheet(
            ui = ui,
            onDismiss = { menuFor = null },
            onSetPlayed = { vm.setPlayed(ui.episode, it) },
            onDownload = { vm.toggleDownload(ui) },
            onDelete = { deleteFor = ui.episode },
            onDetails = if (showDetails) ({ nav.navigate(Screen.PodcastEpisode(ui.episode.id)) }) else null,
            onShow = if (showShowLink && ui.episode.feedUrl.isNotEmpty()) ({ nav.navigate(Screen.PodcastShow(ui.episode.feedUrl)) }) else null,
        )
    }
    deleteFor?.let { e -> DeleteDownloadDialog(e, onDismiss = { deleteFor = null }, onConfirm = { vm.deleteDownload(e) }) }
}

/** "RSS adresiyle ekle" dialog: URL field, adding spinner, the failure in plain words. */
@Composable
internal fun AddRssDialog(
    adding: Boolean,
    error: PodcastErrorKind?,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onEdited: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    ShadeDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = stringResource(R.string.podcast_add_rss),
        text = stringResource(R.string.broadcast_podcast_rss_body),
        confirmLabel = stringResource(if (adding) R.string.broadcast_podcast_rss_adding else R.string.broadcast_podcast_rss_add),
        onConfirm = { if (!adding && url.isNotBlank()) onAdd(url.trim()) },
    ) {
        Spacer(Modifier.height(16.dp))
        ShadeTextField(
            value = url,
            onValueChange = { url = it; if (error != null) onEdited() },
            hint = stringResource(R.string.broadcast_podcast_rss_hint),
            leadingIcon = R.drawable.ic_rss,
            enabled = !adding,
            keyboardType = KeyboardType.Uri,
            onDone = { if (!adding && url.isNotBlank()) onAdd(url.trim()) },
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(podcastErrorText(error), style = MaterialTheme.typography.bodySmall, color = ShadeTheme.extras.error)
        }
    }
}

@Composable
internal fun podcastErrorText(kind: PodcastErrorKind): String = stringResource(
    when (kind) {
        PodcastErrorKind.LocalNetwork -> R.string.broadcast_podcast_error_local
        PodcastErrorKind.NotAFeed -> R.string.broadcast_podcast_error_not_feed
        PodcastErrorKind.Gone -> R.string.broadcast_podcast_error_gone
        PodcastErrorKind.Network -> R.string.state_error_body
    },
)

/** Podcasts.events as snackbars (refresh results, finished / failed downloads). */
@Composable
internal fun PodcastEventMessages() {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    LaunchedEffect(Unit) {
        Podcasts.events.collect { ev ->
            val text = when (ev) {
                is PodcastEvent.NewEpisodes -> if (ev.manual) res.getQuantityString(R.plurals.broadcast_podcast_new_found, ev.count, ev.count) else null
                PodcastEvent.UpToDate -> res.getString(R.string.broadcast_podcast_up_to_date)
                is PodcastEvent.DownloadFinished -> res.getString(R.string.broadcast_podcast_downloaded, ev.episode.title)
                is PodcastEvent.DownloadFailed -> res.getString(
                    R.string.broadcast_podcast_download_failed, ev.episode.title,
                    res.getString(
                        when (ev.kind) {
                            PodcastErrorKind.LocalNetwork -> R.string.broadcast_podcast_error_local
                            PodcastErrorKind.NotAFeed -> R.string.broadcast_podcast_error_not_feed
                            PodcastErrorKind.Gone -> R.string.broadcast_podcast_error_gone
                            PodcastErrorKind.Network -> R.string.state_error_body
                        },
                    ),
                )
                is PodcastEvent.DownloadDeleted -> res.getString(R.string.broadcast_podcast_download_deleted)
            }
            if (text != null) nav.message(text)
        }
    }
}

private val previewEpisode = Episode(
    guid = "g1", feedUrl = "https://example.com/feed", title = "Bölüm 42: Gölgelerin içinden geçen uzun bir başlık",
    show = "Gece Yayını", published = 1_790_000_000, durationMs = 3_600_000,
)

@Preview(backgroundColor = 0xFF0B0C10, showBackground = true)
@Composable
private fun EpisodeRowPreview() {
    ShadeTubeThemeOf {
        Column {
            EpisodeRow(EpisodeUi(previewEpisode, inProgress = true, positionMs = 1_200_000, durationMs = 3_600_000, current = true, playing = true), {}, {}, {})
            EpisodeRow(EpisodeUi(previewEpisode, played = true, durationMs = 3_600_000, download = DownloadUiState.Done), {}, {}, {}, showCover = true)
            EpisodeRow(EpisodeUi(previewEpisode, durationMs = 3_600_000, download = DownloadUiState.Downloading(0.4f)), {}, {}, {})
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SubscriptionTile(Subscription(feedUrl = "a", title = "Gece Yayını", newEpisodes = 3), {})
                SubscriptionTile(Subscription(feedUrl = "b", title = "Bilim Sohbetleri"), {})
            }
        }
    }
}
