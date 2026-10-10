package com.app.shadetube.ui.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.app.PendingOpen
import com.app.shadetube.catalog.Track
import com.app.shadetube.playback.PlayContext
import com.app.shadetube.playback.PlayerNotice
import com.app.shadetube.playback.Status
import com.app.shadetube.playlistio.PlaylistTransfer
import com.app.shadetube.spotify.SessionState
import kotlinx.coroutines.launch

/**
 * TEMPORARY developer screen: verifies the backend end to end on a device (Spotify login, catalog search, playback,
 * opened files / playlist import). Not part of the product UI; the real screens replace it. Texts are deliberately
 * not localized.
 */
@Composable
fun DebugScreen(onSpotifyLogin: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
        ) {
            Text("ShadeTube debug", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            SessionSection(onSpotifyLogin)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TransportSection()
            PendingOpenSection()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SearchSection(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SessionSection(onSpotifyLogin: () -> Unit) {
    val session = AppGraph.session
    val state by session.state.collectAsStateWithLifecycle()
    val profile by session.profile.collectAsStateWithLifecycle()
    val lib by session.library.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Spotify: $state" + if (profile.name.isNotEmpty()) " (${profile.name})" else "")
            if (lib.loaded) Text("${lib.playlists.size} playlists, ${lib.likedIds.size} liked", style = MaterialTheme.typography.bodySmall)
        }
        when (state) {
            SessionState.LoggedOut -> Button(onClick = onSpotifyLogin) { Text("Log in") }
            SessionState.Connecting -> Text("…")
            SessionState.LoggedIn -> OutlinedButton(onClick = { session.logout() }) { Text("Log out") }
        }
    }
}

@Composable
private fun TransportSection() {
    val player = AppGraph.player
    val current by player.current.collectAsStateWithLifecycle()
    val status by player.status.collectAsStateWithLifecycle()
    val position by player.position.collectAsStateWithLifecycle()
    val lastError by player.lastError.collectAsStateWithLifecycle()
    val source by player.source.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(player) {
        player.notices.collect { n: PlayerNotice -> notice = n.toString() }
    }
    Text(current?.let { it.name + " — " + it.artistLine } ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        "$status  ${fmt(position.positionMs)} / ${fmt(position.durationMs)}" +
            (source.match?.let { "  yt:${it.videoId}" } ?: "") + (if (source.local) "  local" else "") + (if (source.codec.isNotEmpty()) "  ${source.codec}" else ""),
        style = MaterialTheme.typography.bodySmall,
    )
    if (lastError != null) Text("Last error: $lastError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (notice.isNotEmpty()) Text("Notice: $notice", style = MaterialTheme.typography.bodySmall, maxLines = 2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { player.previous() }) { Text("Prev") }
        Button(onClick = { player.togglePause() }) { Text(if (status == Status.Playing || status == Status.Buffering) "Pause" else "Play") }
        OutlinedButton(onClick = { player.next() }) { Text("Next") }
    }
    OutlinedButton(
        onClick = {
            val t = PlaylistTransfer.videoTrack(TEST_VIDEO_ID, "Rick Astley - Never Gonna Give You Up", "Rick Astley", 213_000)
            player.playContext(listOf(t), 0, PlayContext("link", "YouTube link"))
        },
        modifier = Modifier.padding(top = 4.dp),
    ) { Text("Play test YouTube track") }
}

@Composable
private fun PendingOpenSection() {
    val pending by AppGraph.pendingOpen.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val p = pending ?: return
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    when (p) {
        PendingOpen.Loading -> Text("Reading opened files…")
        is PendingOpen.AudioFiles -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.tracks.size} opened file(s), ${p.skipped} skipped", Modifier.weight(1f))
            Button(onClick = {
                AppGraph.player.playContext(p.tracks, 0, PlayContext("files", ""))
                AppGraph.consumePendingOpen()
            }) { Text("Play") }
        }
        is PendingOpen.PlaylistImport -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Import \"${p.preview.name}\": ${p.preview.tracks.size} songs, ${p.preview.unresolved.size} unresolved", Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    AppGraph.transfer.commit(p.preview, p.preview.name, alsoOnSpotify = false)
                    AppGraph.consumePendingOpen()
                }
            }) { Text("Import") }
        }
        is PendingOpen.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Open failed: ${p.reason}", Modifier.weight(1f))
            OutlinedButton(onClick = { AppGraph.consumePendingOpen() }) { Text("OK") }
        }
    }
}

@Composable
private fun SearchSection(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        status = "Searching (${if (AppGraph.catalog.loggedIn) "Spotify" else "MusicBrainz"})…"
        scope.launch {
            runCatching { AppGraph.search.search(q) }
                .onSuccess {
                    results = it.tracks
                    status = "${it.tracks.size} tracks, ${it.albums.size} albums, ${it.artists.size} artists"
                }
                .onFailure { status = "Search failed: ${it.message}" }
        }
    }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(results, key = { i, t -> "$i|${t.id}" }) { i, t ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { AppGraph.player.playContext(results, i, PlayContext("search", query.trim())) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artistLine + " · " + t.album.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private const val TEST_VIDEO_ID = "dQw4w9WgXcQ"

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
