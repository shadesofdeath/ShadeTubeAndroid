package com.app.shadetube.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.app.shadetube.core.Hash
import com.app.shadetube.core.toHex
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Verification of a downloaded update (the Android counterpart of the C++ verifyExe, which checked PE x64 GUI,
 * ProductName "ShadeTube" and ProductVersion == the release version): the file's SHA-256 against GitHub's asset digest
 * and the release notes, then the APK itself — same package name, versionName == the release tag's version, and
 * signed with the installed app's certificate (so a tampered or foreign APK is refused before PackageInstaller sees it).
 */
object ApkCheck {
    /** Lowercase hex SHA-256 of a file. */
    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    /** Throws [UpdateException] when [hash] matches neither GitHub's asset digest nor the notes (when they have any). */
    fun checkDigest(hash: String, asset: Asset, release: Release) {
        if (asset.sha256.isNotEmpty() && hash != asset.sha256) throw UpdateException(UpdateError.AssetDigestMismatch)
        if (release.notesSha256.isNotEmpty() && hash !in release.notesSha256) throw UpdateException(UpdateError.NotesDigestMismatch)
    }

    /**
     * Signing check (pure): the APK's signers must be exactly the installed app's, or — with APK Signature Scheme v3 key
     * rotation — its signing history must contain the installed app's current certificate. Certificates are compared by
     * their SHA-256.
     */
    fun signersMatch(installed: List<ByteArray>, apkSigners: List<ByteArray>, apkHistory: List<ByteArray>): Boolean {
        if (installed.isEmpty() || apkSigners.isEmpty()) return false
        val inst = installed.map { Hash.sha256(it).toHex() }.toSet()
        val signers = apkSigners.map { Hash.sha256(it).toHex() }.toSet()
        if (inst == signers) return true
        val history = apkHistory.map { Hash.sha256(it).toHex() }.toSet()
        return inst.size == 1 && inst.first() in history
    }

    private fun signers(info: PackageInfo): Pair<List<ByteArray>, List<ByteArray>> {
        val si = info.signingInfo ?: return emptyList<ByteArray>() to emptyList()
        val current: Array<Signature>? = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.let { h ->
            // The newest certificate is the last of the history.
            h.lastOrNull()?.let { arrayOf(it) }
        }
        val history = if (si.hasMultipleSigners()) emptyArray() else si.signingCertificateHistory ?: emptyArray()
        return (current ?: emptyArray()).map { it.toByteArray() } to history.map { it.toByteArray() }
    }

    /** Throws [UpdateException] unless [apk] is this app, version [expectedVersion], signed like the installed app. */
    fun verifyApk(context: Context, apk: File, expectedVersion: String) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: throw UpdateException(UpdateError.NotAnApk)
        if (info.packageName != context.packageName)
            throw UpdateException(UpdateError.PackageMismatch, "package ${info.packageName}")
        val apkVersion = Versions.normalize(info.versionName.orEmpty())
        if (apkVersion != Versions.normalize(expectedVersion))
            throw UpdateException(UpdateError.VersionMismatch, "version $apkVersion, expected ${Versions.normalize(expectedVersion)}")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val (instSigners, _) = signers(installed)
        val (apkSigners, apkHistory) = signers(info)
        if (!signersMatch(instSigners, apkSigners, apkHistory)) throw UpdateException(UpdateError.SignatureMismatch)
    }
}
