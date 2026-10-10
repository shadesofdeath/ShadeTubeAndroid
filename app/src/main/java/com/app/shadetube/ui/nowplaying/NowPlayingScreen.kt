package com.app.shadetube.ui.nowplaying

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.unit.Dp
import com.app.shadetube.ui.components.LocalWindowLayout
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.playback.Status
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.LikeButton
import com.app.shadetube.ui.components.LinearProgress
import com.app.shadetube.ui.components.LiveBadge
import com.app.shadetube.ui.components.PlayPauseButton
import com.app.shadetube.ui.components.SeekBar
import com.app.shadetube.ui.components.SegmentedPill
import com.app.shadetube.ui.components.ShadeIconButton
import com.app.shadetube.ui.components.SheetDragHandle
import com.app.shadetube.ui.components.SourceBadge
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.components.coverShadow
import com.app.shadetube.ui.components.urlFor
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.NowPlayingPanel
import com.app.shadetube.ui.nav.Screen
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.sheets.SheetMessages
import com.app.shadetube.ui.sheets.TrackLinks
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeRadius
import com.app.shadetube.ui.theme.ShadeTextStyles
import com.app.shadetube.ui.theme.ShadeTheme
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * Şimdi Çalıyor (hi-fi part1/part2, REDLINES): full screen over the shell, the cover shared with the mini player
 * ([coverModifier]). Blurred-cover backdrop with the accent glow, context top bar, 348 cover (fits the height), title /
 * artist / like, seek bar with the buffered band (live: "Canlı" + the station's current song), transport, the source
 * badge + "Yanlış eşleşme?", speed / sleep / output / share (episodes: notes), and the Sıradaki · Şarkı sözleri · İlgili
 * pill that pulls up the panel sheet (the cover then moves into a compact header). Predictive back slides it down.
 */
@Composable
fun NowPlayingScreen(panel: NowPlayingPanel, panelRequest: Int, onClose: () -> Unit, coverModifier: Modifier = Modifier) {
    val player = AppGraph.player
    val track by player.current.collectAsStateWithLifecycle()
    val t = track ?: return
    val vm: NowPlayingViewModel = viewModel()
    val lyricsVm: LyricsViewModel = viewModel()
    val x = ShadeTheme.extras
    val reduce = ShadeTheme.reduceMotion
    val reduceNow by rememberUpdatedState(reduce)
    val scope = rememberCoroutineScope()
    val sheet = remember {
        PanelSheetState(scope) { if (reduceNow) tween(ShadeMotion.SHORT) else ShadeMotion.emphasizedTween(ShadeMotion.MEDIUM) }
    }
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var karaoke by rememberSaveable { mutableStateOf(false) }
    val live by player.isLiveFlow.collectAsStateWithLifecycle()
    val kind = itemKindOf(t, live)
    val bigPx = with(LocalDensity.current) { 348.dp.roundToPx() }
    val bigUrl = remember(t.album.images, bigPx) { t.album.images.urlFor(bigPx) }
    // Tablets / unfolded foldables: two columns with the panel tabs always showing (SCREENS #26).
    val twoPane = LocalWindowLayout.current.twoPaneNowPlaying

    LaunchedEffect(panelRequest) {
        when (panel) {
            NowPlayingPanel.None -> {}
            NowPlayingPanel.Queue -> { tab = 0; if (!twoPane) sheet.expand() }
            NowPlayingPanel.Lyrics -> { tab = 1; if (!twoPane) sheet.expand() }
            NowPlayingPanel.Related -> { tab = 2; if (!twoPane) sheet.expand() }
            NowPlayingPanel.Karaoke -> karaoke = true
        }
    }
    LaunchedEffect(twoPane) { if (twoPane && sheet.isExpanded) sheet.collapse() }

    // Back: karaoke (its own handler) > panel > Now Playing (predictive: it slides down towards the mini player).
    var backProgress by remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = sheet.isExpanded && !karaoke) { sheet.collapse() }
    PredictiveBackHandler(enabled = !sheet.isExpanded && !karaoke) { events ->
        try {
            events.collect { backProgress = it.progress }
            onClose()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = backProgress
                if (p > 0f) {
                    translationY = p * size.height * 0.12f
                    scaleX = 1f - 0.08f * p
                    scaleY = 1f - 0.08f * p
                    shape = RoundedCornerShape((28 * p).dp)
                    clip = true
                }
            }
            .background(x.surface0)
            .pointerInput(Unit) { detectTapGestures { } }, // the shell underneath must not get touches
    ) {
        val density = LocalDensity.current
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (twoPane) {
            TwoPaneNowPlaying(
                t, kind, vm, lyricsVm, bigUrl, coverModifier, tab, onTab = { tab = it }, onClose = onClose,
                onKaraoke = { karaoke = true }, statusTop = statusTop, navBottom = navBottom,
            )
            AnimatedVisibility(karaoke, enter = fadeIn(tween(ShadeMotion.STANDARD)), exit = fadeOut(tween(ShadeMotion.STANDARD))) {
                KaraokeScreen(lyricsVm, t, bigUrl, onClose = { karaoke = false })
            }
            return@BoxWithConstraints
        }
        val collapsedVisible = 92.dp + navBottom
        val expandedTop = statusTop + 48.dp + 72.dp
        val height = maxHeight
        val collapsedTopPx = with(density) { (height - collapsedVisible).toPx() }
        val expandedTopPx = with(density) { expandedTop.toPx() }
        sheet.rangePx = (collapsedTopPx - expandedTopPx).coerceAtLeast(1f)
        val f = sheet.fraction

        NowPlayingBackdrop(bigUrl)

        if (f > 0.02f) {
            FullPlayer(
                t, kind, vm, bigUrl, coverModifier, landscape = maxWidth > maxHeight,
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = statusTop + 48.dp, bottom = collapsedVisible)
                    .graphicsLayer { alpha = f; translationY = -(1f - f) * 48.dp.toPx() },
            )
        }
        if (f < 0.98f) {
            CompactHeader(
                t, kind, onClick = { sheet.collapse() },
                modifier = Modifier.padding(top = statusTop + 48.dp).graphicsLayer { alpha = 1f - f },
            )
        }

        val res = LocalContext.current.resources
        val ctx by player.queue.collectAsStateWithLifecycle()
        val (overline, ctxName) = remember(ctx.context, kind) { NowPlayingTexts.context(res, ctx.context, kind) }
        val nav = LocalNavigator.current
        NowPlayingTopBar(
            overline, ctxName,
            onCollapse = { if (sheet.isExpanded) sheet.collapse() else onClose() },
            onMore = { nav.showTrackMenu(listOf(t), TrackMenuContext(queueIndex = ctx.currentOrderIndex)) },
            modifier = Modifier.padding(top = statusTop),
        )

        // ---- the panel sheet
        val sheetTop = lerp(expandedTopPx, collapsedTopPx, f)
        Column(
            Modifier
                .fillMaxWidth()
                .height(height - expandedTop)
                .offset { IntOffset(0, sheetTop.roundToInt()) }
                .clip(ShadeRadius.sheet)
                .background(x.surface1.copy(alpha = (1f - f).coerceIn(0f, 1f))),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { sheet.dragBy(it) },
                        onDragStopped = { v -> sheet.settle(v) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SheetDragHandle()
                Spacer(Modifier.height(4.dp))
                SegmentedPill(
                    options = listOf(
                        stringResource(R.string.player_tab_queue),
                        stringResource(R.string.player_tab_lyrics),
                        stringResource(R.string.player_tab_related),
                    ),
                    selectedIndex = tab,
                    onSelect = { i ->
                        if (i == tab && sheet.isExpanded) sheet.collapse() else { tab = i; sheet.expand() }
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
            if (f < 0.995f) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val bottomPad = navBottom + 12.dp
                    when (tab) {
                        0 -> QueuePanel(sheet.nestedScroll, bottomPad)
                        1 -> LyricsPanel(lyricsVm, sheet.nestedScroll, bottomPad, onKaraoke = { karaoke = true })
                        else -> RelatedPanel(sheet.nestedScroll, bottomPad, onClose)
                    }
                }
            }
        }

        AnimatedVisibility(karaoke, enter = fadeIn(tween(ShadeMotion.STANDARD)), exit = fadeOut(tween(ShadeMotion.STANDARD))) {
            KaraokeScreen(lyricsVm, t, bigUrl, onClose = { karaoke = false })
        }
    }
}

/**
 * Expanded windows (SCREENS #26): the top bar across, then two columns — left 45 %: the cover (at most 480) with the
 * controls under it; right 55 %: the Sıradaki · Sözler · İlgili tabs, always open (no pull-up sheet).
 */
@Composable
private fun TwoPaneNowPlaying(
    t: Track,
    kind: ItemKind,
    vm: NowPlayingViewModel,
    lyricsVm: LyricsViewModel,
    bigUrl: String?,
    coverModifier: Modifier,
    tab: Int,
    onTab: (Int) -> Unit,
    onClose: () -> Unit,
    onKaraoke: () -> Unit,
    statusTop: Dp,
    navBottom: Dp,
) {
    val x = ShadeTheme.extras
    val player = AppGraph.player
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    val ctx by player.queue.collectAsStateWithLifecycle()
    val (overline, ctxName) = remember(ctx.context, kind) { NowPlayingTexts.context(res, ctx.context, kind) }
    val noNested = remember { object : NestedScrollConnection {} }
    NowPlayingBackdrop(bigUrl)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)),
    ) {
        NowPlayingTopBar(
            overline, ctxName,
            onCollapse = onClose,
            onMore = { nav.showTrackMenu(listOf(t), TrackMenuContext(queueIndex = ctx.currentOrderIndex)) },
            modifier = Modifier.padding(top = statusTop, start = 12.dp, end = 12.dp),
        )
        Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = navBottom + 20.dp)) {
            FullPlayer(
                t, kind, vm, bigUrl, coverModifier,
                modifier = Modifier.weight(0.45f).fillMaxHeight().padding(start = 8.dp),
                maxCover = 480.dp,
            )
            Column(
                Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .padding(start = 16.dp, end = 24.dp, top = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(x.surface1.copy(alpha = 0.72f)),
            ) {
                Spacer(Modifier.height(16.dp))
                SegmentedPill(
                    options = listOf(
                        stringResource(R.string.player_tab_queue),
                        stringResource(R.string.player_tab_lyrics),
                        stringResource(R.string.player_tab_related),
                    ),
                    selectedIndex = tab,
                    onSelect = onTab,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> QueuePanel(noNested, 12.dp)
                        1 -> LyricsPanel(lyricsVm, noNested, 12.dp, onKaraoke = onKaraoke)
                        else -> RelatedPanel(noNested, 12.dp, onClose)
                    }
                }
            }
        }
    }
}

/**
 * Backdrop: API 31+ the cover blurred 60dp at 1.2× under surface0 @70 %; always an accent glow from the top and a
 * fade to surface0 at the bottom (below API 31 the glow alone — the design's own fallback).
 */
@Composable
internal fun NowPlayingBackdrop(url: String?, strong: Boolean = false) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    Box(Modifier.fillMaxSize().background(x.surface0)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && url != null) {
            Artwork(
                url,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                    .blur(60.dp),
                kind = ArtworkKind.Track,
                shape = RectangleShape,
            )
            Box(Modifier.fillMaxSize().background(x.surface0.copy(alpha = if (strong) 0.78f else 0.70f)))
        }
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.glow, Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.28f),
                        radius = size.width * 1.1f,
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent, 1f to x.surface0,
                    ),
                )
            },
        )
    }
}

@Composable
private fun FullPlayer(
    t: Track,
    kind: ItemKind,
    vm: NowPlayingViewModel,
    bigUrl: String?,
    coverModifier: Modifier,
    modifier: Modifier,
    landscape: Boolean = false,
    maxCover: Dp = Dp.Unspecified,
) {
    val player = AppGraph.player
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val res = context.resources
    val x = ShadeTheme.extras
    val status by player.status.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val source by player.source.collectAsStateWithLifecycle()
    val speed by player.speed.collectAsStateWithLifecycle()
    val liveTitle by player.liveTitle.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val canLike by vm.canLike.collectAsStateWithLifecycle()
    val shuffle by vm.shuffleMode.collectAsStateWithLifecycle()
    val sleep by vm.sleepMinutes.collectAsStateWithLifecycle()
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val isPlaying = status == Status.Playing || status == Status.Buffering || status == Status.Resolving
    val station = kind == ItemKind.Station
    var speedSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    val output = rememberOutputLabel()

    // The cover: fits the space it gets (portrait: the height left above the controls; landscape: the left column).
    val cover: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .sizeIn(maxWidth = maxCover, maxHeight = maxCover)
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .coverGlow(spread = 1.25f)
                    .coverShadow(30.dp, ShadeRadius.coverLarge),
            ) {
                Artwork(
                    bigUrl,
                    coverModifier.fillMaxSize(),
                    kind = when (kind) {
                        ItemKind.Station -> ArtworkKind.Radio
                        ItemKind.Episode -> ArtworkKind.Podcast
                        else -> ArtworkKind.Track
                    },
                    shape = ShadeRadius.coverLarge,
                )
            }
        }
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        // title / artist · album / like
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = ShadeTextStyles.nowPlayingTitle, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = if (station && liveTitle.isNotEmpty()) liveTitle else NowPlayingTexts.artistAlbum(t)
                if (sub.isNotEmpty()) {
                    Text(
                        sub, style = MaterialTheme.typography.bodyMedium, color = x.text2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canLike) {
                LikeButton(liked, onToggle = { SheetMessages.like(res, vm.toggleLike())?.let { nav.message(it) } })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (station) LiveRow(liveTitle, status) else SeekSection()
        TransportRow(
            isPlaying = isPlaying,
            shuffle = shuffle,
            repeat = queue.repeat,
            live = station,
            onShuffle = { AppGraph.smartShuffle.cycleMode() },
            onPrev = player::previous,
            onPlayPause = player::togglePause,
            onNext = player::next,
            onRepeat = { player.cycleRepeat() },
        )
        Spacer(Modifier.height(4.dp))
        // source badge + "Yanlış eşleşme?"
        val badge = NowPlayingTexts.source(res, source, status, kind, downloaded)
        val canPick = kind == ItemKind.Song && !source.local && !source.live && !source.direct
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (badge != null) SourceBadge(badge)
            if (canPick) {
                Spacer(Modifier.width(4.dp))
                WrongMatchButton { nav.showSourcePicker(t) }
            }
        }
        // secondary tools
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!station) {
                ToolButton(
                    R.drawable.ic_speed, stringResource(R.string.player_speed_value, NowPlayingTexts.speed(speed)),
                    stringResource(R.string.player_speed), active = speed != 1f, mono = true,
                ) { speedSheet = true }
            }
            val sleepLabel = when {
                sleep > 0 -> stringResource(R.string.player_sleep_remaining, sleep.toInt())
                sleep < 0 -> stringResource(R.string.np_sleep_end_short)
                else -> stringResource(R.string.np_sleep_short)
            }
            ToolButton(R.drawable.ic_sleep_timer, sleepLabel, stringResource(R.string.player_sleep_timer), active = sleep != 0L, mono = sleep > 0) {
                sleepSheet = true
            }
            ToolButton(
                if (output.second) R.drawable.ic_headphones else R.drawable.ic_speaker, output.first,
                stringResource(R.string.player_output), active = false, mono = false,
            ) {
                if (!OutputSwitcher.open(context)) nav.message(res.getString(R.string.np_output_failed))
            }
            if (kind == ItemKind.Episode) {
                ToolButton(R.drawable.ic_info, stringResource(R.string.np_notes), stringResource(R.string.np_notes), active = false, mono = false) {
                    nav.navigate(Screen.PodcastEpisode(t.id))
                }
            } else {
                ToolButton(R.drawable.ic_share, stringResource(R.string.player_share), stringResource(R.string.player_share), active = false, mono = false) {
                    val url = TrackLinks.url(t, source.match?.videoId)
                    if (!TrackLinks.share(context, t, url)) nav.message(res.getString(R.string.np_share_none))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (landscape) {
        // Landscape phones: cover on the left, the controls (scrollable when they do not fit) on the right.
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            cover(Modifier.fillMaxHeight().padding(start = 24.dp, top = 8.dp, bottom = 8.dp).aspectRatio(1f, matchHeightConstraintsFirst = true))
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) { controls() }
        }
    } else {
        Column(modifier) {
            Spacer(Modifier.height(12.dp))
            cover(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp))
            Spacer(Modifier.height(24.dp))
            controls()
        }
    }
    if (speedSheet) SpeedSheet { speedSheet = false }
    if (sleepSheet) SleepSheet(sleep) { sleepSheet = false }
}

/** Seek bar with the buffered band; position ticks recompose only this. */
@Composable
private fun SeekSection() {
    val player = AppGraph.player
    val pos by player.position.collectAsStateWithLifecycle()
    SeekBar(
        positionMs = pos.positionMs,
        durationMs = pos.durationMs,
        onSeek = player::seek,
        bufferedMs = (pos.bufferedFraction * pos.durationMs).toLong(),
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

/** Live station: "Canlı" + the current song (or "Bağlanıyor…" with an indeterminate bar). */
@Composable
private fun LiveRow(liveTitle: String, status: Status) {
    val x = ShadeTheme.extras
    val connecting = status == Status.Buffering || status == Status.Resolving
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveBadge()
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    connecting -> stringResource(R.string.radio_connecting)
                    liveTitle.isNotEmpty() -> stringResource(R.string.np_on_air, liveTitle)
                    else -> stringResource(R.string.radio_now_on_air)
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = x.text2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgress(if (connecting) null else 1f, Modifier.fillMaxWidth(), height = 4.dp)
        Spacer(Modifier.height(4.dp))
    }
}

/** Expanded panel: cover 56 + title / artist + play / next (72 high) under the top bar. Tap = collapse. */
@Composable
private fun CompactHeader(t: Track, kind: ItemKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val player = AppGraph.player
    val x = ShadeTheme.extras
    val status by player.status.collectAsStateWithLifecycle()
    val pos by player.position.collectAsStateWithLifecycle()
    val px = with(LocalDensity.current) { 56.dp.roundToPx() }
    val url = remember(t.album.images, px) { t.album.images.urlFor(px) }
    Column(modifier.fillMaxWidth().height(72.dp)) {
        Row(
            Modifier.fillMaxWidth().weight(1f).clickable(onClick = onClick).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url, size = 56.dp,
                kind = when (kind) {
                    ItemKind.Station -> ArtworkKind.Radio
                    ItemKind.Episode -> ArtworkKind.Podcast
                    else -> ArtworkKind.Track
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayPauseButton(status == Status.Playing || status == Status.Buffering || status == Status.Resolving, player::togglePause)
            if (kind != ItemKind.Station) {
                ShadeIconButton(R.drawable.ic_next, stringResource(R.string.player_next_cd), player::next, tint = x.text1, size = 48.dp)
            }
        }
        if (kind != ItemKind.Station && pos.durationMs > 0) {
            LinearProgress(
                (pos.positionMs.toFloat() / pos.durationMs).coerceIn(0f, 1f),
                Modifier.fillMaxWidth().padding(horizontal = 20.dp), height = 2.dp,
            )
        }
    }
}

/** Current output: (label, headphones / Bluetooth connected), refreshed when devices come and go. */
@Composable
private fun rememberOutputLabel(): Pair<String, Boolean> {
    val context = LocalContext.current
    val phone = stringResource(R.string.player_output_phone)
    val headphones = stringResource(R.string.player_output_headphones)
    var label by remember { mutableStateOf(OutputSwitcher.label(context, phone, headphones)) }
    DisposableEffect(context) {
        val am = context.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                label = OutputSwitcher.label(context, phone, headphones)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                label = OutputSwitcher.label(context, phone, headphones)
            }
        }
        am?.registerAudioDeviceCallback(cb, null)
        onDispose { am?.unregisterAudioDeviceCallback(cb) }
    }
    return label
}
