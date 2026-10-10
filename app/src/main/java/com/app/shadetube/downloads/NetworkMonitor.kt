package com.app.shadetube.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.app.shadetube.core.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The connection's state for download gating (replaces the C++ WinRT `NetworkInformation` connection-cost check that
 * DownloadSync ran every minute on a worker). Android pushes changes: the default network's capabilities are watched
 * with [ConnectivityManager.registerDefaultNetworkCallback]. Metered = the network lacks NET_CAPABILITY_NOT_METERED
 * (mobile data, a metered hotspot); unknown (no callback yet) = the current active network's state.
 */
class NetworkMonitor(context: Context) : NetworkState {
    data class Snapshot(val connected: Boolean = true, val metered: Boolean = false)

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    override val connected: Boolean get() = _state.value.connected
    override val metered: Boolean get() = _state.value.metered

    private fun initial(): Snapshot {
        val c = cm ?: return Snapshot()
        return runCatching {
            val n = c.activeNetwork ?: return Snapshot(connected = false, metered = false)
            snapshotOf(c.getNetworkCapabilities(n))
        }.getOrDefault(Snapshot())
    }

    private fun snapshotOf(caps: NetworkCapabilities?): Snapshot {
        if (caps == null) return Snapshot(connected = false, metered = false)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return Snapshot(connected = internet, metered = metered)
    }

    private fun publish(s: Snapshot) {
        val old = _state.value
        if (old == s) return
        _state.value = s
        if (old.metered != s.metered || old.connected != s.connected)
            Log.i("sync", "connection ${if (!s.connected) "lost" else if (s.metered) "metered" else "not metered"}")
    }

    /** Starts watching (once, at startup). */
    fun start() {
        val c = cm ?: return
        runCatching {
            c.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish(snapshotOf(caps))
                override fun onLost(network: Network) = publish(Snapshot(connected = false, metered = false))
            })
        }.onFailure { Log.w("sync", "network callback unavailable", it) }
    }
}
