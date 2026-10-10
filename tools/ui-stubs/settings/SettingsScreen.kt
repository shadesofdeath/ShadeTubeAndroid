package com.app.shadetube.ui.settings

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
fun SettingsScreen(contentPadding: PaddingValues, section: com.app.shadetube.ui.nav.SettingsSection) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("SettingsScreen") }
}

@Composable
fun EqualizerScreen(contentPadding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("EqualizerScreen") }
}

@Composable
fun BlocklistScreen(contentPadding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("BlocklistScreen") }
}
