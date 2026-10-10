package com.app.shadetube.ui.nav

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * "Re-tapping the selected tab scrolls its root screen to the top." The shell calls [request] when the selected tab is
 * tapped again while its root screen is showing (deeper in the stack the tap pops to the root instead); a tab's root
 * screen hooks its scrolling container up with one of the [ScrollToTopEffect] overloads. Requests are fire-and-forget:
 * a screen that is not composed simply misses them.
 */
object ScrollToTopRequests {
    private val flow = MutableSharedFlow<Tab>(extraBufferCapacity = 4)
    val requests: SharedFlow<Tab> = flow.asSharedFlow()

    fun request(tab: Tab) {
        flow.tryEmit(tab)
    }
}

/** Far down a list a smooth scroll would take ages: jump close to the top first, then animate the rest. */
private const val JUMP_FROM_ITEM = 8

@Composable
fun ScrollToTopEffect(tab: Tab, state: LazyListState) {
    LaunchedEffect(tab, state) {
        ScrollToTopRequests.requests.filter { it == tab }.collect {
            if (state.firstVisibleItemIndex > JUMP_FROM_ITEM) state.scrollToItem(JUMP_FROM_ITEM / 2)
            state.animateScrollToItem(0)
        }
    }
}

@Composable
fun ScrollToTopEffect(tab: Tab, state: LazyGridState) {
    LaunchedEffect(tab, state) {
        ScrollToTopRequests.requests.filter { it == tab }.collect {
            if (state.firstVisibleItemIndex > JUMP_FROM_ITEM * 3) state.scrollToItem(JUMP_FROM_ITEM)
            state.animateScrollToItem(0)
        }
    }
}

@Composable
fun ScrollToTopEffect(tab: Tab, state: ScrollState) {
    LaunchedEffect(tab, state) {
        ScrollToTopRequests.requests.filter { it == tab }.collect { state.animateScrollTo(0) }
    }
}
