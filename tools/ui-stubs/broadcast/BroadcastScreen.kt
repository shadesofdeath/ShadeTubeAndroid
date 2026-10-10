package com.app.shadetube.ui.broadcast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// STUB: placeholder until the screen is implemented (contract: docs/UI.md, ui/nav/Navigation.kt).

@Composable
fun BroadcastScreen(contentPadding: PaddingValues, tab: com.app.shadetube.ui.nav.BroadcastTab) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("BroadcastScreen") }
}

@Composable
fun RadioListScreen(contentPadding: PaddingValues, kind: com.app.shadetube.ui.nav.RadioListKind, value: String, title: String) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("RadioListScreen") }
}

@Composable
fun PodcastShowScreen(contentPadding: PaddingValues, feedOrAppleId: String) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("PodcastShowScreen") }
}

@Composable
fun PodcastEpisodeScreen(contentPadding: PaddingValues, episodeId: String) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("PodcastEpisodeScreen") }
}
