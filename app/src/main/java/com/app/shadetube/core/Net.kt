package com.app.shadetube.core

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy

/*
 * OkHttp helpers shared by the packages that talk to user-supplied / directory-supplied hosts (podcasts, internet
 * radio). Consolidated from their package-private copies.
 */

/**
 * Runs a blocking OkHttp call on the IO pool so that cancelling the coroutine cancels the call (also in the middle of a
 * body read). [block] reads the response; it is closed afterwards.
 */
suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = withContext(AppDispatchers.io) {
    coroutineScope {
        val call = this@executeCancellable
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use(block)
        } finally {
            watcher.cancel()
        }
    }
}

/**
 * A network interceptor that refuses to talk to this device or its local network: it sees the address every hop
 * (redirects included) really connects to, so a public name that resolves to 192.168.x.x or a redirect to localhost is
 * caught too. Behind a proxy the address is the proxy's: then only the URL check before the request applies.
 */
object PublicOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val route = chain.connection()?.route()
        if (route != null && route.proxy.type() == Proxy.Type.DIRECT) {
            val addr = route.socketAddress.address
            if (addr != null && Http.isLocalHost(addr.hostAddress ?: "", resolve = false)) {
                throw IOException("an address on the local network")
            }
        }
        return chain.proceed(chain.request())
    }
}

/** Reads at most [maxBytes] of a body; more throws [tooLarge]. */
fun Response.readCapped(maxBytes: Long, tooLarge: () -> IOException): ByteArray {
    val declared = body.contentLength()
    if (declared > maxBytes) throw tooLarge()
    val out = ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 16384)
    val buf = ByteArray(16384)
    body.byteStream().use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw tooLarge()
            out.write(buf, 0, n)
        }
    }
    return out.toByteArray()
}
