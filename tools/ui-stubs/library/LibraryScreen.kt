package com.app.shadetube.ui.library

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
fun LibraryScreen(contentPadding: PaddingValues, folderId: String) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("LibraryScreen") }
}
