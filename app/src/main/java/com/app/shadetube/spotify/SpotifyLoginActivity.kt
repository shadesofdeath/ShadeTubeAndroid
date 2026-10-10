package com.app.shadetube.spotify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.app.shadetube.core.Log

/**
 * Spotube-style Spotify login (port of app/LoginWindow): a WebView shows accounts.spotify.com; when the user signs in,
 * Spotify sets the long-lived `sp_dc` cookie on .spotify.com. We watch for it and hand it to [Session.loginWithCookie]
 * — that cookie is all [Auth] needs to mint web-player tokens. No credentials ever pass through ShadeTube.
 *
 * Finishes with RESULT_OK once the cookie was captured and handed over; RESULT_CANCELED when the user backs out (back
 * first walks the WebView's own history, like closing the C++ window only at the start page).
 *
 * User agent: the C++ used WebView2's defaults (desktop Edge). Here the system WebView's own mobile UA is kept with
 * the "; wv" token and the "Version/x.y" marker removed, so it reads as mobile Chrome of the same engine version:
 * Google / Facebook sign-in refuse UAs that identify an embedded WebView, and the mobile login page fits the screen.
 */
class SpotifyLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    /** Login redirects through several pages and the web app changes URLs without a load: also poll on a timer. */
    private val poller = object : Runnable {
        override fun run() {
            pollCookie()
            if (!finished) handler.postDelayed(this, POLL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        // Edge-to-edge (targetSdk 35+): keep the page clear of the system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true) // Apple / Google / Facebook sign-in hops across domains

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // window.open / target=_blank ("Continue with Apple / Google / Facebook" popups) load in this same view, so
            // those sign-in methods work (the C++ redirected NewWindowRequested into its one WebView the same way).
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = loginUserAgent(userAgentString)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Stay in the page: never hand intent:// / spotify: links to another app.
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            // Re-check for the cookie after every completed navigation (login redirects through several pages).
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                pollCookie()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = pollCookie()
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else cancel()
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        if (webView.url == null) webView.loadUrl(START_URL)
        handler.postDelayed(poller, POLL_MS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun pollCookie() {
        if (finished) return
        val cm = CookieManager.getInstance()
        for (url in COOKIE_URLS) {
            val value = findCookie(cm.getCookie(url), "sp_dc")
            if (!value.isNullOrEmpty()) {
                complete(value)
                return
            }
        }
    }

    private fun complete(spDc: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(poller)
        val session = Session.current
        if (session == null) {
            Log.e("spotify", "login: no Session registered; dropping the captured cookie")
            setResult(Activity.RESULT_CANCELED)
        } else {
            session.loginWithCookie(spDc)
            setResult(Activity.RESULT_OK)
        }
        clearWebData()
        finish()
    }

    private fun cancel() {
        if (finished) return
        finished = true
        handler.removeCallbacks(poller)
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * The C++ kept the WebView2 profile (appData/webview2) as is. On Android the cookie jar is shared by every WebView
     * of the app and the sp_dc now lives encrypted in spotify.dat, so the plain copy is wiped: a later login (after
     * logout, or with another account) then starts from a clean page instead of instantly re-capturing the old cookie.
     */
    private fun clearWebData() {
        runCatching {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearFormData()
            webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }.onFailure { Log.w("spotify", "login: clearing WebView data failed", it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Spotify sets sp_dc on .spotify.com after sign-in; open.spotify.com can read it. We land the WebView there. */
        const val START_URL = "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        private val COOKIE_URLS = listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com")
        private const val POLL_MS = 1_000L

        /** The value of cookie [name] in a CookieManager "a=b; c=d" header string. */
        fun findCookie(header: String?, name: String): String? {
            if (header.isNullOrEmpty()) return null
            for (part in header.split(';')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                if (part.substring(0, eq).trim() == name) return part.substring(eq + 1).trim()
            }
            return null
        }

        /** The WebView's default UA without the embedded-WebView markers ("; wv", "Version/4.0 "). */
        fun loginUserAgent(default: String): String =
            default.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")
    }
}

/** Launches [SpotifyLoginActivity]; the result is true when the cookie was captured and handed to the Session. */
class SpotifyLoginContract : ActivityResultContract<Unit, Boolean>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(context, SpotifyLoginActivity::class.java)
    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}
