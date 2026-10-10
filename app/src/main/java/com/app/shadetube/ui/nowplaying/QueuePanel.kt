package com.app.shadetube.ui.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.catalog.Track
import com.app.shadetube.downloads.DlState
import com.app.shadetube.playback.Status
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.SwipeToDeleteBox
import com.app.shadetube.ui.components.TrackRow
import com.app.shadetube.ui.nav.ExportRequest
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.TrackMenuContext
import com.app.shadetube.ui.theme.ShadeTheme

/** Drag-to-reorder of the upcoming rows: the dragged row follows the finger and swaps with the row under its center. */
@Stable
private class ReorderState(private val list: LazyListState) {
    var draggingKey by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    var order by mutableStateOf<List<Int>>(emptyList())
    var fromIndex = -1

    fun start(key: String, itemIndex: Int) {
        draggingKey = key
        offset = 0f
        fromIndex = order.indexOf(itemIndex)
    }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        offset += dy
        val visible = list.layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.key == key } ?: return
        val center = info.offset + offset + info.size / 2f
        val target = visible.firstOrNull {
            val k = it.key as? String
            k != null && k.startsWith(KEY_UPCOMING) && k != key && center >= it.offset && center <= it.offset + it.size
        } ?: return
        val a = order.indexOfFirst { KEY_UPCOMING + it == key }
        val b = order.indexOfFirst { KEY_UPCOMING + it == target.key }
        if (a < 0 || b < 0) return
        order = order.toMutableList().apply { add(b, removeAt(a)) }
        offset -= (target.offset - info.offset)
    }

    /** (from, to) display indexes of the finished drag, or null. */
    fun end(): Pair<Int, Int>? {
        val key = draggingKey ?: return null
        val to = order.indexOfFirst { KEY_UPCOMING + it == key }
        val from = fromIndex
        draggingKey = null
        offset = 0f
        fromIndex = -1
        return if (from >= 0 && to >= 0 && from != to) from to to else null
    }
}

private const val KEY_UPCOMING = "q"

/**
 * Sıradaki (SCREENS #13): the current row; "Sıradaki" with "Sırayı temizle" / "Dışa aktar"; upcoming rows with a 40dp
 * drag handle (reorder -> [Player.move]), swipe left to remove (undo snackbar), suggested rows with a 2dp
 * accent.container strip and add / hide; "Az önce çalanlar" collapsible in text3.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueuePanel(nestedScroll: NestedScrollConnection, bottomPadding: Dp) {
    val nav = LocalNavigator.current
    val res = LocalContext.current.resources
    val player = AppGraph.player
    val smart = AppGraph.smartShuffle
    val accent = ShadeTheme.accent
    val x = ShadeTheme.extras
    val snap by player.queue.collectAsStateWithLifecycle()
    val status by player.status.collectAsStateWithLifecycle()
    val downloads by AppGraph.downloads.state.collectAsStateWithLifecycle()
    val blockRev by AppGraph.blacklist.revision.collectAsStateWithLifecycle()
    val done = remember(downloads) { downloads.items.filter { it.state == DlState.Done }.mapTo(HashSet()) { it.track.id } }
    val isPlaying = status == Status.Playing || status == Status.Buffering
    val listState = rememberLazyListState()
    val reorder = remember(listState) { ReorderState(listState) }
    var showRecent by rememberSaveable { mutableStateOf(false) }

    val pos = snap.currentOrderIndex
    val first = if (pos < 0) 0 else pos + 1
    val upcomingItems = remember(snap) { snap.order.drop(first) }
    if (reorder.draggingKey == null) reorder.order = upcomingItems
    val shown = reorder.order
    val current = snap.current
    val recent = remember(snap) { if (pos > 0) snap.order.take(pos).withIndex().reversed() else emptyList() }

    fun remove(orderPos: Int, t: Track) {
        player.removeAt(orderPos)
        nav.message(res.getString(R.string.queue_removed), res.getString(R.string.action_undo)) { player.insertAt(orderPos, listOf(t)) }
    }

    LazyColumn(
        Modifier.fillMaxSize().nestedScroll(nestedScroll),
        state = listState,
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        if (current != null) {
            item("h_now") { SectionLabel(stringResource(R.string.queue_now_playing)) }
            item("now") {
                TrackRow(
                    current, isCurrent = true, isPlaying = isPlaying, downloaded = current.id in done,
                    onMore = { nav.showTrackMenu(listOf(current), TrackMenuContext(queueIndex = pos)) },
                    onClick = { player.togglePause() },
                )
            }
        }
        item("h_next") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.queue_up_next), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = x.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (upcomingItems.isNotEmpty()) {
                    ShadeTextButton(stringResource(R.string.queue_clear), {
                        val saved = snap.upcoming
                        player.clearQueue()
                        nav.message(res.getString(R.string.np_queue_cleared), res.getString(R.string.action_undo)) { player.enqueue(saved) }
                    }, color = x.text2)
                }
                if (snap.items.isNotEmpty()) {
                    ShadeTextButton(stringResource(R.string.queue_export), { nav.showExport(ExportRequest.Queue) }, color = x.text2)
                }
            }
        }
        if (shown.isEmpty()) {
            item("empty") {
                Text(
                    stringResource(R.string.np_queue_empty), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = x.text3,
                )
            }
        }
        items(shown, key = { KEY_UPCOMING + it }) { itemIndex ->
            val key = KEY_UPCOMING + itemIndex
            val t = snap.items.getOrNull(itemIndex) ?: return@items
            val orderPos = first + shown.indexOf(itemIndex)
            val dragging = reorder.draggingKey == key
            val blocked = remember(t.id, blockRev) { AppGraph.blacklist.isBlocked(t) }
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) reorder.offset else 0f }
                    .then(if (dragging) Modifier else Modifier.animateItem()),
            ) {
                SwipeToDeleteBox(onDelete = { remove(orderPos, t) }, enabled = !dragging) {
                    val strip = accent.container
                    TrackRow(
                        t,
                        modifier = if (t.recommended) Modifier.drawBehind { drawRect(strip, size = Size(2.dp.toPx(), size.height)) } else Modifier,
                        downloaded = t.id in done,
                        blocked = blocked,
                        dragging = dragging,
                        contentPadding = PaddingValues(start = 20.dp, end = 0.dp),
                        onClick = { player.jumpTo(orderPos) },
                        onMore = { nav.showTrackMenu(listOf(t), TrackMenuContext(queueIndex = orderPos)) },
                        onAddSuggested = if (t.recommended && smart.canAdd("", t)) ({ smart.add("", t) }) else null,
                        onHideSuggested = if (t.recommended) ({
                            val k = smart.queueCollectionKey()
                            if (k.isNotEmpty()) smart.hide(k, t)
                            player.removeAt(orderPos)
                        }) else null,
                        trailing = {
                            Box(
                                Modifier
                                    .size(40.dp, 48.dp)
                                    .pointerInput(key) {
                                        detectDragGestures(
                                            onDragStart = { reorder.start(key, itemIndex) },
                                            onDrag = { change, amount -> change.consume(); reorder.drag(amount.y) },
                                            onDragEnd = {
                                                reorder.end()?.let { (a, b) ->
                                                    val f = player.currentOrderIndex.let { c -> if (c < 0) 0 else c + 1 }
                                                    player.move(f + a, f + b)
                                                }
                                            },
                                            onDragCancel = { reorder.end() },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) { ShadeIcon(R.drawable.ic_drag_handle, stringResource(R.string.queue_drag_cd), tint = x.text3) }
                        },
                    )
                }
            }
        }
        if (recent.isNotEmpty()) {
            item("h_recent") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showRecent = !showRecent }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.queue_recently_played), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, color = x.text3,
                    )
                    ShadeIcon(if (showRecent) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, null, tint = x.text3)
                }
            }
            if (showRecent) {
                items(recent, key = { "r" + it.value }) { (orderPos, itemIndex) ->
                    val t = snap.items.getOrNull(itemIndex) ?: return@items
                    TrackRow(
                        t, downloaded = t.id in done,
                        onClick = { player.jumpTo(orderPos) },
                        onMore = { nav.showTrackMenu(listOf(t), TrackMenuContext(queueIndex = orderPos)) },
                    )
                }
            }
        }
        item("end") { Spacer(Modifier.size(16.dp)) }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium, color = ShadeTheme.extras.text1,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
