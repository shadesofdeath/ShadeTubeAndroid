@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.app.shadetube.ui.sheets

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.ui.components.Artwork
import com.app.shadetube.ui.components.ArtworkKind
import com.app.shadetube.ui.components.EmptyState
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SkeletonBox
import com.app.shadetube.ui.components.SkeletonLine
import com.app.shadetube.ui.components.SourceBadge
import com.app.shadetube.ui.components.formatDuration
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.shell.ShellMessages
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.youtube.Match
import com.app.shadetube.youtube.matchFailureOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface Candidates {
    data object Loading : Candidates
    data class Failed(val reason: String?) : Candidates
    data class Loaded(val matches: List<Match>, val currentVideoId: String) : Candidates
}

/**
 * "Kaynağı değiştir" (SCREENS #14): the YouTube candidates for [track] ([MatchService.candidates], best first, the
 * current match on top and marked). 88dp rows: thumbnail 112×64 · title 2 lines 14/600 · channel 12 text2 · duration
 * mono · match badge (≥90 accent, 70–89 warning, <70 text3) · "Önizle" 36 pill (30 s on a separate player, see
 * [SourcePreview]; the row being previewed shows "Durdur"). Tap = select: the playing track switches at once
 * ([Player.useMatch]), another one is pinned for next time ([MatchService.pin]).
 */
@Composable
fun SourcePickerSheet(track: Track, onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    var state by remember { mutableStateOf<Candidates>(Candidates.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val preview = rememberSourcePreview(onFailed = { nav.message(res.getString(R.string.sheets_preview_failed)) })
    LaunchedEffect(track.id, attempt) {
        state = Candidates.Loading
        val playing = AppGraph.player.currentTrack?.id == track.id
        val current = (if (playing) AppGraph.player.source.value.match?.videoId else null)
            ?: runCatching { AppGraph.matchService.cachedMatch(track.id)?.videoId }.getOrNull().orEmpty()
        state = AppGraph.matchService.candidates(track).fold(
            onSuccess = { Candidates.Loaded(it, current) },
            onFailure = { Candidates.Failed(ShellMessages.matchFailure(res, matchFailureOf(it))) },
        )
    }
    ShadeBottomSheet(onDismissRequest = { preview.stop(); onDismiss() }) {
        SheetHeader(stringRes(R.string.source_title), subtitle = listOf(track.name, track.artistLine).filter { it.isNotEmpty() }.joinToString(" · "))
        when (val s = state) {
            Candidates.Loading -> Column(Modifier.padding(bottom = 16.dp)) { repeat(4) { CandidateSkeleton() } }
            is Candidates.Failed -> ErrorState(onRetry = { attempt++ }, body = s.reason)
            is Candidates.Loaded -> if (s.matches.isEmpty()) {
                EmptyState(R.drawable.ic_search, stringRes(R.string.sheets_source_none_title), stringRes(R.string.sheets_source_none_body))
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(s.matches, key = { it.videoId }) { m ->
                        CandidateRow(
                            m, current = m.videoId == s.currentVideoId,
                            preview = when {
                                !preview.isActive(m.videoId) -> SourcePreview.Phase.Idle
                                else -> preview.phase
                            },
                            onPreview = { preview.toggle(m) },
                        ) {
                            preview.stop()
                            onDismiss()
                            if (m.videoId == s.currentVideoId) return@CandidateRow
                            if (AppGraph.player.currentTrack?.id == track.id) {
                                AppGraph.player.useMatch(m)
                            } else {
                                sheetScope.launch { AppGraph.matchService.pin(track.id, m) }
                            }
                            nav.message(res.getString(R.string.sheets_source_changed))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CandidateRow(
    m: Match,
    current: Boolean,
    preview: SourcePreview.Phase,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val score = m.score.coerceIn(0.0, 100.0).roundToInt()
    val scoreColor: Color = when {
        score >= 90 -> accent.accent
        score >= 70 -> x.warning
        else -> x.text3
    }
    val selectLabel = stringRes(R.string.source_select)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .background(if (current) accent.container else Color.Transparent)
            .clickable(onClickLabel = selectLabel, role = Role.Button, onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(m.thumbnailUrl.ifEmpty { "https://i.ytimg.com/vi/${m.videoId}/mqdefault.jpg" }, Modifier.size(112.dp, 64.dp), kind = ArtworkKind.Track)
            if (m.durationSec > 0) {
                Text(
                    formatDuration(m.durationSec * 1000L),
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(x.scrim)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    style = ShadeMono.small.copy(fontSize = 10.sp), color = Color.White,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, color = x.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.channel, style = MaterialTheme.typography.bodySmall, color = x.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(
                    if (m.manual) stringRes(R.string.sheets_source_manual) else LocalResources.current.getString(R.string.source_match, score),
                    color = if (m.manual) accent.accent else scoreColor,
                )
                if (current) {
                    Spacer(Modifier.width(8.dp))
                    ShadeIcon(R.drawable.ic_check, null, tint = accent.accent, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(stringRes(R.string.source_current), style = MaterialTheme.typography.labelMedium, color = accent.accent)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        PreviewPill(m.title, preview, onPreview)
    }
}

/** "Önizle" 36 pill: play icon + label; loading = spinner; playing = pause icon + "Durdur" in accent. */
@Composable
private fun PreviewPill(title: String, phase: SourcePreview.Phase, onClick: () -> Unit) {
    val x = ShadeTheme.extras
    val accent = ShadeTheme.accent
    val active = phase != SourcePreview.Phase.Idle
    val cd = if (active) stringRes(R.string.sheets_preview_stop_cd) else LocalResources.current.getString(R.string.sheets_preview_cd, title)
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) accent.container else x.surface3)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cd }
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                SourcePreview.Phase.Loading -> CircularProgressIndicator(
                    Modifier.size(16.dp), color = accent.accent, strokeWidth = 2.dp, trackColor = Color.Transparent,
                )
                SourcePreview.Phase.Playing -> ShadeIcon(R.drawable.ic_pause, null, tint = accent.accent, size = 18.dp)
                SourcePreview.Phase.Idle -> ShadeIcon(R.drawable.ic_play, null, tint = x.text1, size = 18.dp)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringRes(if (active) R.string.sheets_preview_stop else R.string.source_preview),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (active) accent.accent else x.text1, maxLines = 1,
        )
    }
}

@Composable
private fun CandidateSkeleton() {
    Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SkeletonBox(Modifier.size(112.dp, 64.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(180.dp)
            SkeletonLine(120.dp)
            SkeletonLine(60.dp)
        }
    }
}
