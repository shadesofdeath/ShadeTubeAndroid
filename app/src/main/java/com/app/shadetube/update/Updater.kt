package com.app.shadetube.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.app.shadetube.core.AppDispatchers
import com.app.shadetube.core.Files
import com.app.shadetube.core.Http
import com.app.shadetube.core.HttpRequest
import com.app.shadetube.core.Log
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.core.Time
import com.app.shadetube.core.appScope
import com.app.shadetube.core.dump
import com.app.shadetube.core.jsonOf
import com.app.shadetube.core.long
import com.app.shadetube.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/** What Ayarlar › Hakkında shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** [latest] = the newest release (a dev build may be newer than it). */
    data class UpToDate(val current: String, val latest: Release?) : UpdateState
    /** The repository has no public release yet. */
    data object NoReleases : UpdateState
    /** [asset] null: the release has no Android package (nothing to install). */
    data class Available(val release: Release, val asset: Asset?) : UpdateState {
        val version: String get() = release.version
        val notes: String get() = release.body
    }
    /** [total] < 0: unknown. */
    data class Downloading(val release: Release, val done: Long, val total: Long) : UpdateState {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Verifying(val release: Release) : UpdateState
    data class ReadyToInstall(val release: Release, val apk: File) : UpdateState
    data class Installing(val release: Release) : UpdateState
    /** The system wants the user to confirm the install: start [intent] (the receiver also tries). */
    data class NeedsUserAction(val release: Release, val intent: Intent) : UpdateState
    /** [release] set: the download / install can be tried again. */
    data class Error(val error: UpdateError, val detail: String = "", val release: Release? = null) : UpdateState
}

/** What the C++ toasted; the UI words them. */
sealed interface UpdateEvent {
    /** "ShadeTube X yayımlandı" (with "İndir ve kur" when [installable]). Not sent at startup for a skipped version. */
    data class Announce(val release: Release, val installable: Boolean, val manual: Boolean) : UpdateEvent
    data class UpToDate(val current: String) : UpdateEvent
    data object NoReleases : UpdateEvent
    data class CheckFailed(val error: UpdateError, val detail: String) : UpdateEvent
    data class Failed(val error: UpdateError, val detail: String) : UpdateEvent
    data object Cancelled : UpdateEvent
    data class Skipped(val version: String) : UpdateEvent
    /** The app runs the version an update installed ("ShadeTube X sürümüne güncellendi"). */
    data class Updated(val version: String) : UpdateEvent
}

/**
 * Self-update from GitHub releases (port of app/Updater + the update flow of app/AboutSettings), for the APK:
 *
 *   check     GET <releases/latest> (GitHub API) -> [Release]; 404 = no public release yet (not an error). Automatic:
 *             once, ~20 s after startup, at most once a day (Settings.updateCheck / lastUpdateCheck); a version the user
 *             skipped (skippedVersion) stays quiet then, asking explicitly always shows it.
 *   download  the release's .apk (prefer names with "android") in ranged 1 MB chunks into Paths.tempDir/update,
 *             resumed after an interruption; a server that ignores Range (200) is handled too.
 *   verify    SHA-256 against GitHub's asset digest and any "sha256: <hex>" line of the release notes, then the APK:
 *             same package, versionName == the tag's version, same signing certificate ([ApkCheck]).
 *   install   a PackageInstaller session; [InstallStatusReceiver] reports the result (or the confirmation the system
 *             asks for). Needs REQUEST_INSTALL_PACKAGES and the user's "install unknown apps" permission.
 *   cleanup   APKs of versions the app already runs are deleted at startup.
 */
class Updater(
    private val context: Context,
    private val scope: CoroutineScope = appScope,
    private val updateUrl: String = Releases.LATEST_URL,
    private val client: OkHttpClient = Http.client,
    private val dir: () -> File = { File(Paths.tempDir, "update") },
    private val currentVersionProvider: () -> String = { installedVersion(context) },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<UpdateEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UpdateEvent> = _events.asSharedFlow()

    private var autoCheckDone = false
    private var job: Job? = null

    /** The running version ("0.6.0"). */
    val currentVersion: String get() = Versions.normalize(currentVersionProvider())

    /** "ShadeTube/<ver> (+https://github.com/shadesofdeath/ShadeTubeAndroid)". */
    val userAgent: String get() = "ShadeTube/$currentVersion (+https://github.com/shadesofdeath/ShadeTubeAndroid)"

    // ---- startup ------------------------------------------------------------------------------------------------------

    /**
     * Call once at startup: cleans up update files, reports a finished update, and schedules the automatic check
     * (~20 s after startup, off the startup path, at most once a day).
     */
    fun start() {
        UpdateRuntime.updater = this
        scope.launch(AppDispatchers.io) {
            cleanup()
            delay(AUTO_CHECK_DELAY_MS)
            autoCheck()
        }
    }

    private fun autoCheck() {
        if (autoCheckDone) return
        autoCheckDone = true
        val st = SettingsStore.current
        val now = Time.nowUnix()
        if (!st.updateCheck || (st.lastUpdateCheck > 0 && now >= st.lastUpdateCheck && now - st.lastUpdateCheck < 86400)) return
        check(manual = false)
    }

    /** APKs of the version we run (or older) are leftovers of an installed update. */
    private fun cleanup() {
        val pending = Paths.file(PENDING_FILE)
        val current = currentVersion
        if (pending.exists()) {
            val v = runCatching { pending.readText().trim() }.getOrDefault("")
            if (v.isNotEmpty() && Versions.compare(v, current) == 0) _events.tryEmit(UpdateEvent.Updated(current))
            if (v.isEmpty() || Versions.compare(v, current) <= 0) pending.delete()
        }
        dir().listFiles()?.forEach { f ->
            val v = f.name.removePrefix("ShadeTube-").substringBefore(".apk")
            if (f.name.startsWith("ShadeTube-") && Versions.compare(v, current) <= 0) f.delete()
        }
    }

    // ---- check --------------------------------------------------------------------------------------------------------

    /** A check (Ayarlar › "Güncellemeleri denetle" passes manual = true). */
    fun check(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Verifying || s is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        scope.launch(AppDispatchers.io) {
            try {
                val result = fetchLatest()
                SettingsStore.update { it.copy(lastUpdateCheck = Time.nowUnix()) }
                if (result == null) {
                    _state.value = UpdateState.NoReleases
                    if (manual) _events.tryEmit(UpdateEvent.NoReleases)
                    return@launch
                }
                val current = currentVersion
                if (Versions.compare(result.version, current) > 0) {
                    val asset = Releases.pickAsset(result)
                    _state.value = UpdateState.Available(result, asset)
                    Log.i(TAG, "latest release ${result.version} (running $current): update available")
                    // A version the user skipped stays quiet at startup; asking explicitly always shows it.
                    if (manual || result.version != SettingsStore.current.skippedVersion) {
                        // A release without an Android package offers nothing to install: quiet at startup.
                        if (asset != null || manual) _events.tryEmit(UpdateEvent.Announce(result, asset != null, manual))
                    }
                } else {
                    Log.i(TAG, "latest release ${result.version} (running $current): up to date")
                    _state.value = UpdateState.UpToDate(current, result)
                    if (manual) _events.tryEmit(UpdateEvent.UpToDate(current))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                Log.w(TAG, "check failed: ${e.message}")
                _state.value = UpdateState.Error(e.error, e.message.orEmpty())
                if (manual) _events.tryEmit(UpdateEvent.CheckFailed(e.error, e.message.orEmpty()))
            }
        }
    }

    /** One request. Null = no public release (404). Throws [UpdateException]. */
    private suspend fun fetchLatest(): Release? {
        val resp = try {
            Http.await(
                HttpRequest(
                    updateUrl,
                    headers = listOf("User-Agent" to userAgent, "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"),
                ),
                client,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check $updateUrl: ${e.message}")
            throw UpdateException(UpdateError.Network, e.message ?: "network", e)
        }
        if (resp.status == 404) { // the repository has no (public) release yet
            Log.i(TAG, "no published release ($updateUrl)")
            return null
        }
        if (resp.status == 403 || resp.status == 429) throw UpdateException(UpdateError.RateLimited, "HTTP ${resp.status}")
        if (!resp.isSuccess) throw UpdateException(UpdateError.HttpStatus, resp.status.toString())
        return try {
            Releases.parse(resp.body)
        } catch (e: UpdateException) {
            Log.w(TAG, "release JSON: ${e.message}")
            throw e
        }
    }

    /** "Bu sürümü atla". */
    fun skip() {
        val s = _state.value as? UpdateState.Available ?: return
        SettingsStore.update { it.copy(skippedVersion = s.release.version) }
        _state.value = UpdateState.Idle
        _events.tryEmit(UpdateEvent.Skipped(s.release.version))
    }

    // ---- download + verify --------------------------------------------------------------------------------------------

    /** "İndir ve kur": download + verify the APK of the available release, then [install] it. */
    fun downloadAndInstall() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Error -> s.release ?: return
            is UpdateState.ReadyToInstall -> return install()
            else -> return
        }
        val asset = Releases.pickAsset(release) ?: run {
            _events.tryEmit(UpdateEvent.Failed(UpdateError.NoPackage, release.version))
            return
        }
        _state.value = UpdateState.Downloading(release, 0, if (asset.size > 0) asset.size else -1)
        job = scope.launch(AppDispatchers.io) {
            try {
                val apk = fetchAndVerify(release, asset)
                _state.value = UpdateState.ReadyToInstall(release, apk)
                withContext(AppDispatchers.main) { install() }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release, asset)
                _events.tryEmit(UpdateEvent.Cancelled)
            } catch (e: UpdateException) {
                Log.w(TAG, "update to ${release.version} failed: ${e.error} ${e.message}")
                _state.value = UpdateState.Error(e.error, e.message.orEmpty(), release)
                _events.tryEmit(UpdateEvent.Failed(e.error, e.message.orEmpty()))
            } catch (e: Exception) {
                Log.w(TAG, "update to ${release.version} failed", e)
                _state.value = UpdateState.Error(UpdateError.Io, e.message.orEmpty(), release)
                _events.tryEmit(UpdateEvent.Failed(UpdateError.Io, e.message.orEmpty()))
            } finally {
                job = null
            }
        }
    }

    /** "Vazgeç" while downloading. The partial file stays for a later resume. */
    fun cancel() {
        job?.cancel()
    }

    private suspend fun fetchAndVerify(release: Release, asset: Asset): File {
        val d = dir().apply { mkdirs() }
        val base = "ShadeTube-${release.version}"
        val apk = File(d, "$base.apk")
        val part = File(d, "$base.apk.part")
        val meta = File(d, "$base.apk.json")
        // A finished, verified-before download of the same asset: verify again (cheap) instead of downloading.
        if (!(apk.isFile && sameAsset(meta, asset) && runCatching { verify(apk, asset, release) }.isSuccess)) {
            apk.delete()
            val resumeFrom = if (part.isFile && sameAsset(meta, asset)) part.length() else 0L
            Files.writeAtomic(meta, jsonOf("url" to asset.url, "size" to asset.size).dump())
            Log.i(TAG, "downloading ${asset.name} (${asset.size} bytes) from ${asset.url}" + if (resumeFrom > 0) ", resuming at $resumeFrom" else "")
            RangedDownload.download(client, asset.url, part, resumeFrom, userAgent) { done, total ->
                _state.value = UpdateState.Downloading(release, done, if (total > 0) total else asset.size.takeIf { it > 0 } ?: -1)
            }
            coroutineContext.ensureActive()
            _state.value = UpdateState.Verifying(release)
            try {
                verify(part, asset, release)
            } catch (e: UpdateException) {
                // A file that fails verification is never resumed or kept.
                part.delete()
                meta.delete()
                throw e
            }
            if (!part.renameTo(apk)) throw UpdateException(UpdateError.Io, "rename")
        }
        return apk
    }

    private fun sameAsset(meta: File, asset: Asset): Boolean {
        val j = Files.readJson(meta) ?: return false
        return j.str("url") == asset.url && j.long("size") == asset.size
    }

    private fun verify(file: File, asset: Asset, release: Release) {
        ApkCheck.checkDigest(ApkCheck.sha256File(file), asset, release)
        ApkCheck.verifyApk(context, file, release.version)
    }

    // ---- install ------------------------------------------------------------------------------------------------------

    /** Whether the user allowed ShadeTube to install apps (Android 8+ "install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system setting where the user allows ShadeTube to install updates. */
    fun installPermissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Installs the verified APK through a PackageInstaller session. */
    fun install() {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return
        if (!canInstall()) {
            _state.value = UpdateState.Error(UpdateError.InstallPermission, "", s.release)
            _events.tryEmit(UpdateEvent.Failed(UpdateError.InstallPermission, ""))
            // Keep the file: the next tap ("İndir ve kur") verifies it again and installs.
            return
        }
        _state.value = UpdateState.Installing(s.release)
        scope.launch(AppDispatchers.io) {
            try {
                commitSession(s.release, s.apk)
            } catch (e: Exception) {
                Log.w(TAG, "install session failed", e)
                _state.value = UpdateState.Error(UpdateError.InstallFailed, e.message.orEmpty(), s.release)
                _events.tryEmit(UpdateEvent.Failed(UpdateError.InstallFailed, e.message.orEmpty()))
            }
        }
    }

    private fun commitSession(release: Release, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // An update of ourselves by ourselves (the installer of record) can go without a confirmation dialog.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("ShadeTube.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            // Recorded before the commit: the next start reports "updated to X" and cleans up.
            Files.writeAtomic(Paths.file(PENDING_FILE), release.version)
            val intent = Intent(context, InstallStatusReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "install session $id for ${release.version} committed")
    }

    /** From [InstallStatusReceiver]. */
    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val release = when (val s = _state.value) {
            is UpdateState.Installing -> s.release
            is UpdateState.NeedsUserAction -> s.release
            else -> null
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm != null && release != null) _state.value = UpdateState.NeedsUserAction(release, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally the process is replaced before this arrives.
                Log.i(TAG, "update installed")
                _state.value = UpdateState.Idle
            }
            else -> {
                val error = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UpdateError.Cancelled else UpdateError.InstallFailed
                Log.w(TAG, "install failed: status $status ${message.orEmpty()}")
                Paths.file(PENDING_FILE).delete()
                _state.value = UpdateState.Error(error, message.orEmpty(), release)
                _events.tryEmit(if (error == UpdateError.Cancelled) UpdateEvent.Cancelled else UpdateEvent.Failed(error, message.orEmpty()))
            }
        }
    }

    companion object {
        private const val TAG = "update"
        const val CHUNK = 1L shl 20
        private const val AUTO_CHECK_DELAY_MS = 20_000L
        private const val PENDING_FILE = "update-pending.txt"

        @Suppress("DEPRECATION")
        fun installedVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"
    }
}


/**
 * The updater's download (port of updater::download): ranged GETs (1 MB) so progress can be reported, resumed at
 * `startAt` after an interruption; a server that ignores Range (200) is handled too. Throws [UpdateException];
 * cancellable (the in-flight request is aborted).
 */
object RangedDownload {
    suspend fun download(
        client: OkHttpClient,
        url: String,
        out: File,
        startAt: Long,
        userAgent: String,
        progress: (Long, Long) -> Unit,
    ) = withContext(AppDispatchers.io) {
        RandomAccessFile(out, "rw").use { file ->
            var done = startAt.coerceIn(0, file.length())
            file.setLength(done)
            file.seek(done)
            var total = -1L
            var request = 0
            while (true) {
                coroutineContext.ensureActive()
                if (++request > 100_000) throw UpdateException(UpdateError.Download, "too many requests")
                val req = Request.Builder().url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/octet-stream")
                    // Byte ranges of the file itself, never of a gzip stream (OkHttp then also leaves the body alone).
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$done-${done + Updater.CHUNK - 1}")
                    .build()
                val (status, body, range) = try {
                    executeCancellable(client.newCall(req)) { r -> Triple(r.code, if (r.code == 200 || r.code == 206) r.body.bytes() else ByteArray(0), r.header("Content-Range").orEmpty()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    Log.w("update", "download $url at $done: ${e.message}")
                    throw UpdateException(UpdateError.Download, e.message ?: "connection lost", e)
                }
                when {
                    status == 206 -> {
                        // Content-Range: bytes <first>-<last>/<total or *>
                        val sp = range.indexOf(' ')
                        val dash = range.indexOf('-', maxOf(sp, 0))
                        val first = if (sp >= 0) range.substring(sp + 1, if (dash < 0) range.length else dash).trim().toLongOrNull() ?: -1 else -1
                        val slash = range.indexOf('/')
                        if (slash >= 0 && !range.substring(slash + 1).startsWith("*")) total = range.substring(slash + 1).trim().toLongOrNull() ?: total
                        if (first != done) throw UpdateException(UpdateError.Download, "unexpected range $range at $done")
                        file.write(body)
                        done += body.size
                    }
                    status == 200 -> {
                        // The server ignores Range: this response is the whole file.
                        file.setLength(0)
                        file.seek(0)
                        file.write(body)
                        done = body.size.toLong()
                        total = done
                    }
                    status == 416 && total >= 0 && done >= total -> break
                    status == 416 && done > 0 && total < 0 -> break // resumed a file that was already complete
                    else -> throw UpdateException(UpdateError.Download, "HTTP $status")
                }
                progress(done, total)
                if (if (total >= 0) done >= total else body.size < Updater.CHUNK) break
                if (body.isEmpty()) throw UpdateException(UpdateError.Download, "empty response")
            }
            file.fd.sync()
        }
    }

    private suspend fun <T> executeCancellable(call: Call, block: (Response) -> T): T = coroutineScope {
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

/** Where [InstallStatusReceiver] finds the updater (set by [Updater.start]). */
object UpdateRuntime {
    @Volatile var updater: Updater? = null
}
