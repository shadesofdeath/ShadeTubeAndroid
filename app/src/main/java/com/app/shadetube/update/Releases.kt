package com.app.shadetube.update

import com.app.shadetube.core.arr
import com.app.shadetube.core.get
import com.app.shadetube.core.isObject
import com.app.shadetube.core.parseJsonOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** Why an update step failed (the UI maps it to text; the C++ threw messages in the UI language). */
enum class UpdateError {
    /** "GitHub'a ulaşılamadı. İnternet bağlantını kontrol et." */
    Network,
    /** 403 / 429: "GitHub istek sınırına ulaşıldı. Biraz sonra tekrar dene." */
    RateLimited,
    /** Another HTTP status: "GitHub beklenmeyen bir yanıt verdi (HTTP n)." (detail = the status). */
    HttpStatus,
    /** "Sürüm bilgisi okunamadı." */
    BadRelease,
    /** The release has no Android package (.apk). */
    NoPackage,
    /** The download broke off / the server misbehaved ("İndirme bağlantısı koptu…", "Sunucu beklenmeyen bir parça gönderdi."). */
    Download,
    /** The file could not be written. */
    Io,
    /** "İndirilen dosyanın SHA-256 özeti GitHub'dakiyle tutmuyor." */
    AssetDigestMismatch,
    /** "İndirilen dosyanın SHA-256 özeti sürüm notlarındakiyle tutmuyor." */
    NotesDigestMismatch,
    /** Not a readable APK. */
    NotAnApk,
    /** The APK is another app (package name). */
    PackageMismatch,
    /** The APK's versionName is not the release's version. */
    VersionMismatch,
    /** The APK is not signed with the installed app's certificate. */
    SignatureMismatch,
    /** "Install unknown apps" is not allowed for ShadeTube (open [Updater.installPermissionIntent]). */
    InstallPermission,
    /** PackageInstaller refused / failed (detail = its message). */
    InstallFailed,
    /** "Güncelleme iptal edildi". */
    Cancelled,
}

class UpdateException(val error: UpdateError, message: String = error.name, cause: Throwable? = null) : Exception(message, cause)

// ---- Versions ---------------------------------------------------------------------------------------------------------

data class Version(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    /** Pre-release identifiers ("beta.1"); empty = a release. */
    val pre: String = "",
)

object Versions {
    /**
     * "0.4.0", "v0.4.0", "0.4" (= 0.4.0), "0.4.0-beta.1", "0.4.0+build.7" (build metadata ignored). Null = not a version.
     */
    fun parse(input: String): Version? {
        var s = input.trimStart(' ', '\t').trimEnd(' ', '\t', '\r', '\n')
        if (s.isNotEmpty() && (s[0] == 'v' || s[0] == 'V')) s = s.substring(1)
        val plus = s.indexOf('+')
        if (plus >= 0) s = s.substring(0, plus) // build metadata
        var pre = ""
        val dash = s.indexOf('-')
        if (dash >= 0) {
            pre = s.substring(dash + 1)
            s = s.substring(0, dash)
            if (pre.isEmpty()) return null
        }
        val parts = IntArray(3)
        var n = 0
        var rest = s
        while (rest.isNotEmpty()) {
            if (n == 3) return null
            val dot = rest.indexOf('.')
            val part = if (dot < 0) rest else rest.substring(0, dot)
            if (part.isEmpty() || part.length > 9 || !part.all { it in '0'..'9' }) return null
            parts[n++] = part.toInt()
            if (dot < 0) break
            rest = rest.substring(dot + 1)
            if (rest.isEmpty()) return null // "1.2."
        }
        if (n == 0) return null
        return Version(parts[0], parts[1], parts[2], pre)
    }

    /** Semver precedence: < 0 when a is older than b, 0 when equal, > 0 when newer. Unparsable = 0.0.0. */
    fun compare(a: String, b: String): Int {
        val va = parse(a) ?: Version()
        val vb = parse(b) ?: Version()
        for ((x, y) in listOf(va.major to vb.major, va.minor to vb.minor, va.patch to vb.patch))
            if (x != y) return if (x < y) -1 else 1
        // A pre-release precedes the release; otherwise compare the dot-separated identifiers (semver §11).
        if (va.pre.isEmpty() || vb.pre.isEmpty()) {
            return if (va.pre.isEmpty() == vb.pre.isEmpty()) 0 else if (va.pre.isEmpty()) 1 else -1
        }
        val ia = va.pre.split('.')
        val ib = vb.pre.split('.')
        var i = 0
        while (true) {
            val x = ia[i]
            val y = ib[i]
            val na = x.isNotEmpty() && x.all { it in '0'..'9' }
            val nb = y.isNotEmpty() && y.all { it in '0'..'9' }
            if (na && nb) {
                if (x.length != y.length) return if (x.length < y.length) -1 else 1 // numeric: longer = larger
                if (x != y) return if (x < y) -1 else 1
            } else if (na != nb) {
                return if (na) -1 else 1 // numeric identifiers have lower precedence
            } else if (x != y) {
                return if (x < y) -1 else 1
            }
            val ea = i == ia.size - 1
            val eb = i == ib.size - 1
            if (ea || eb) return if (ea == eb) 0 else if (ea) -1 else 1 // fewer identifiers = lower precedence
            ++i
        }
    }

    /** "v0.4" -> "0.4.0"; an unparsable tag is returned trimmed, without a leading 'v'. */
    fun normalize(tag: String): String {
        parse(tag)?.let { v -> return "${v.major}.${v.minor}.${v.patch}" + if (v.pre.isEmpty()) "" else "-${v.pre}" }
        var t = tag.trimEnd(' ', '\r', '\n').trimStart(' ')
        if (t.isNotEmpty() && (t[0] == 'v' || t[0] == 'V')) t = t.substring(1)
        return t
    }
}

// ---- Releases ---------------------------------------------------------------------------------------------------------

data class Asset(
    val name: String,
    /** browser_download_url. */
    val url: String,
    val size: Long = 0,
    /** From the asset's "digest" ("sha256:<hex>", GitHub adds it), lowercase; may be empty. */
    val sha256: String = "",
)

data class Release(
    val tag: String,
    /** normalize(tag). */
    val version: String,
    val name: String = "",
    val body: String = "",
    val htmlUrl: String = "",
    val publishedAt: String = "",
    val assets: List<Asset> = emptyList(),
    /** Every "sha256: <hex>" of the release notes, lowercase. */
    val notesSha256: List<String> = emptyList(),
)

object Releases {
    const val LATEST_URL = "https://api.github.com/repos/shadesofdeath/ShadeTubeAndroid/releases/latest"

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** 64-hex tokens on lines that mention sha256. */
    fun findSha256(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split('\n')) {
            if (!line.lowercase(Locale.ROOT).contains("sha256")) continue
            var i = 0
            while (i < line.length) {
                if (!isHex(line[i])) {
                    ++i
                    continue
                }
                var j = i
                while (j < line.length && isHex(line[j])) ++j
                if (j - i == 64) out.add(line.substring(i, j).lowercase(Locale.ROOT))
                i = j
            }
        }
        return out
    }

    private fun JsonElement?.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    /** Throws [UpdateException] (BadRelease) on malformed JSON ("parse") / a missing tag_name ("tag_name"). */
    fun parse(json: String): Release {
        val j = parseJsonOrNull(json) ?: throw UpdateException(UpdateError.BadRelease, "release: parse error")
        val tag = j.string("tag_name")
        if (!j.isObject || tag.isEmpty()) throw UpdateException(UpdateError.BadRelease, "release: tag_name missing")
        val assets = ArrayList<Asset>()
        for (a in j["assets"].arr) {
            if (!a.isObject) continue
            val name = a.string("name")
            val url = a.string("browser_download_url")
            val size = (a["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0
            // GitHub computes "digest": "sha256:<hex>" for every uploaded asset.
            val d = a.string("digest").lowercase(Locale.ROOT)
            val sha = if (d.length == 7 + 64 && d.startsWith("sha256:") && d.substring(7).all(::isHex)) d.substring(7) else ""
            if (name.isNotEmpty() && url.isNotEmpty()) assets.add(Asset(name, url, size, sha))
        }
        val body = j.string("body")
        return Release(
            tag = tag,
            version = Versions.normalize(tag),
            name = j.string("name"),
            body = body,
            htmlUrl = j.string("html_url"),
            publishedAt = j.string("published_at"),
            assets = assets,
            notesSha256 = findSha256(body),
        )
    }

    /**
     * The Android package of a release: `ShadeTube-<ver>-android.apk`, else any .apk whose name mentions "android",
     * else any .apk. Null when there is none (a Windows-only release).
     */
    fun pickAsset(r: Release): Asset? {
        val exact = "shadetube-" + r.version.lowercase(Locale.ROOT) + "-android.apk"
        var android: Asset? = null
        var any: Asset? = null
        for (a in r.assets) {
            val n = a.name.lowercase(Locale.ROOT)
            if (n == exact) return a
            if (!n.endsWith(".apk")) continue
            if (n.contains("android")) { if (android == null) android = a } else if (any == null) any = a
        }
        return android ?: any
    }
}
