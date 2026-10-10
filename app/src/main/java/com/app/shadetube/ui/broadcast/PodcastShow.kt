package com.app.shadetube.ui.broadcast

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.podcasts.Episode
import com.app.shadetube.podcasts.Show
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.ShadeTopBar
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.components.formatDuration
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTheme

// ---------------------------------------------------------------------------------------------------- show

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastShowContent(contentPadding: PaddingValues, feedOrAppleId: String) {
    val nav = LocalNavigator.current
    val vm: PodcastShowViewModel = viewModel(key = "podcast-show:$feedOrAppleId") { PodcastShowViewModel(feedOrAppleId) }
    val load by vm.show.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val show = (load as? Load.Ready)?.value ?: vm.placeholder

    PodcastEventMessages()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShadeTopBar(onNavigate = { nav.back() }, title = null)
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            EpisodeInteractions(vm) { onLongClick, onDownload ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                ) {
                    item("header") {
                        ShowHeader(
                            show = show,
                            subscribed = subscribed,
                            canAct = load is Load.Ready,
                            onSubscribe = vm::toggleSubscribe,
                            onPlay = vm::playLatest,
                        )
                    }
                    when (val l = load) {
                        Load.Loading -> skeletonItems("ep", 6) { EpisodeRowSkeleton() }
                        is Load.Failed -> item("err") {
                            val kind = com.app.shadetube.podcasts.PodcastErrorKind.of(l.error)
                            ErrorState(onRetry = vm::load, body = podcastErrorText(kind))
                        }
                        is Load.Ready -> {
                            item("eps-h") {
                                Spacer(Modifier.height(8.dp))
                                SectionHeader(
                                    stringResource(R.string.podcast_episodes),
                                    subtitle = pluralStringResource(R.plurals.broadcast_podcast_episode_count, l.value.episodes.size, l.value.episodes.size),
                                )
                            }
                            if (rows.isEmpty() && l.value.episodes.isEmpty()) {
                                item("eps-empty") {
                                    EmptyState(R.drawable.ic_podcast, stringResource(R.string.broadcast_podcast_no_episodes), null)
                                }
                            }
                            itemsIndexed(rows, key = { _, u -> u.episode.id }) { i, u ->
                                EpisodeRow(
                                    u,
                                    onClick = { vm.play(i) },
                                    onLongClick = { onLongClick(u) },
                                    onDownload = { onDownload(u) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Cover 148 (glow) + title + publisher; subscribe pill + play; the description, expandable. */
@Composable
private fun ShowHeader(show: Show?, subscribed: Boolean, canAct: Boolean, onSubscribe: () -> Unit, onPlay: () -> Unit) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (show == null) {
                SkeletonBox(Modifier.size(148.dp), ShadeRadius.cover)
            } else {
                Artwork(show.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = 148.dp, modifier = Modifier.coverGlow(spread = 0.8f))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (show == null) {
                    SkeletonLine(120.dp, height = 18.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonLine(80.dp)
                } else {
                    Text(
                        show.title, style = MaterialTheme.typography.headlineSmall, color = x.text1,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    if (show.author.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(show.author, style = MaterialTheme.typography.bodyMedium, color = x.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (show.categories.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // Apple / RSS category names are English: upper-case them without the UI locale ("SOCIETY", not "SOCİETY").
                            show.categories.take(2).joinToString(" · ").uppercase(java.util.Locale.ROOT),
                            style = ShadeMono.small, color = x.text3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (subscribed) {
                SecondaryButton(stringResource(R.string.podcast_subscribed), onSubscribe, icon = R.drawable.ic_check, enabled = canAct)
            } else {
                PrimaryButton(stringResource(R.string.podcast_subscribe), onSubscribe, icon = R.drawable.ic_plus, enabled = canAct)
            }
            Spacer(Modifier.weight(1f))
            val playCd = stringResource(R.string.player_play_cd)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canAct) accent.accent else x.surface3)
                    .clickable(enabled = canAct, role = Role.Button, onClickLabel = playCd, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                ShadeIcon(R.drawable.ic_play, playCd, tint = if (canAct) accent.onAccent else x.text3)
            }
        }
        val about = show?.description.orEmpty()
        if (about.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ExpandableText(AnnotatedString(about), collapsedLines = 3)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Text that shows [collapsedLines] lines and a "Devamını göster" toggle when it is longer. */
@Composable
private fun ExpandableText(text: AnnotatedString, collapsedLines: Int, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(modifier.animateContentSize(expandSpec())) {
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = x.text2,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            ShadeTextButton(
                stringResource(if (expanded) R.string.broadcast_show_less else R.string.broadcast_show_more),
                { expanded = !expanded }, modifier = Modifier.padding(start = 0.dp),
            )
        }
    }
}

@Composable
private fun expandSpec(): FiniteAnimationSpec<IntSize> = ShadeMotion.orReduced(ShadeMotion.standardTween())

// ---------------------------------------------------------------------------------------------------- episode

@Composable
internal fun PodcastEpisodeContent(contentPadding: PaddingValues, episodeId: String) {
    val nav = LocalNavigator.current
    val vm: PodcastEpisodeViewModel = viewModel(key = "podcast-episode:$episodeId") { PodcastEpisodeViewModel(episodeId) }
    val load by vm.episode.collectAsStateWithLifecycle()
    val row by vm.row.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    PodcastEventMessages()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShadeTopBar(onNavigate = { nav.back() }, title = null)
        when (val l = load) {
            Load.Loading -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBox(Modifier.size(96.dp), ShadeRadius.cover)
                SkeletonLine(260.dp, height = 24.dp)
                SkeletonLine(180.dp)
            }
            is Load.Failed -> EmptyState(
                R.drawable.ic_podcast, stringResource(R.string.broadcast_podcast_episode_missing_title),
                stringResource(R.string.broadcast_podcast_episode_missing_body),
            )
            is Load.Ready -> {
                val e = l.value
                val ui = row ?: EpisodeUi(e)
                EpisodeBody(
                    e, ui,
                    bottomPadding = contentPadding.calculateBottomPadding(),
                    onShow = { if (e.feedUrl.isNotEmpty()) nav.navigate(Screen.PodcastShow(e.feedUrl)) },
                    onResume = vm::resume,
                    onFromStart = vm::playFromStart,
                    onTogglePlayed = { vm.setPlayed(e, !ui.played) },
                    onDownload = { if (ui.download == com.app.shadetube.ui.components.DownloadUiState.Done) confirmDelete = true else vm.toggleDownload(ui) },
                )
                if (confirmDelete) DeleteDownloadDialog(e, onDismiss = { confirmDelete = false }, onConfirm = { vm.deleteDownload(e) })
            }
        }
    }
}

@Composable
private fun EpisodeBody(
    e: Episode,
    ui: EpisodeUi,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onShow: () -> Unit,
    onResume: () -> Unit,
    onFromStart: () -> Unit,
    onTogglePlayed: () -> Unit,
    onDownload: () -> Unit,
) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val locale = LocalConfiguration.current.locales[0]
    val notes = remember(e.notesHtml, e.description, accent.accent) {
        if (e.notesHtml.isNotEmpty()) {
            AnnotatedString.fromHtml(
                e.notesHtml,
                linkStyles = TextLinkStyles(SpanStyle(color = accent.accent, textDecoration = TextDecoration.Underline)),
            )
        } else AnnotatedString(e.description)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding + 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(e.image.ifEmpty { null }, kind = ArtworkKind.Podcast, size = 96.dp, modifier = Modifier.coverGlow(spread = 0.8f))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.show, Modifier.clickable(role = Role.Button, onClick = onShow),
                    style = MaterialTheme.typography.titleSmall, color = accent.accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val meta = listOf(
                    formatEpisodeDate(e.published, locale).uppercase(locale),
                    if (ui.durationMs > 0) formatDuration(ui.durationMs) else "",
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = ShadeMono.small, color = x.text3, maxLines = 1)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(e.title, style = MaterialTheme.typography.displaySmall, color = x.text1, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        EpisodeProgressLine(ui)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = stringResource(
                when {
                    ui.playing -> R.string.player_pause_cd
                    ui.inProgress -> R.string.podcast_resume
                    else -> R.string.action_play
                },
            ),
            onClick = onResume,
            icon = if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.inProgress || ui.current) {
                SecondaryButton(stringResource(R.string.broadcast_podcast_from_start), onFromStart, icon = R.drawable.ic_refresh, horizontalPadding = 14)
            }
            SecondaryButton(
                stringResource(if (ui.played) R.string.podcast_mark_unplayed else R.string.podcast_mark_played),
                onTogglePlayed, icon = if (ui.played || ui.inProgress || ui.current) null else R.drawable.ic_check, modifier = Modifier.weight(1f),
                horizontalPadding = 14, // two pills + download on a 360–412dp screen: "Oynatıldı işaretle" fits
            )
            EpisodeDownloadButton(ui.download, onDownload)
        }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.broadcast_podcast_notes).uppercase(locale),
                style = ShadeMono.overline, color = x.text3,
            )
            Spacer(Modifier.height(8.dp))
            ExpandableText(notes, collapsedLines = 8)
        }
    }
}
