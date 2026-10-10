package com.app.shadetube.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Replacement for Windows DPAPI: small secrets (the Spotify sp_dc cookie in spotify.dat, scrobbling credentials in
 * scrobble.dat) are encrypted with an AES-256-GCM key that lives in the Android Keystore and never leaves it.
 * File format: 1 byte version (1) | 12 byte IV | ciphertext+tag.
 */
object SecureStore {
    private const val KEY_ALIAS = "shadetube.secure.v1"
    private const val VERSION: Byte = 1

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(VERSION) + iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray? = runCatching {
        require(blob.size > 13 && blob[0] == VERSION)
        val iv = blob.copyOfRange(1, 13)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.doFinal(blob, 13, blob.size - 13)
    }.getOrNull()

    /** Encrypts and writes `name` in the app data dir. */
    fun save(name: String, plain: String): Boolean =
        runCatching { Files.writeAtomic(File(Paths.appData, name), encrypt(plain.toByteArray())) }
            .onFailure { Log.w("secure", "save $name failed", it) }.getOrDefault(false)

    /** Reads and decrypts; "" on a miss or failure (like the C++ loadCookie). */
    fun load(name: String): String {
        val f = File(Paths.appData, name)
        if (!f.exists()) return ""
        return runCatching { decrypt(f.readBytes())?.toString(Charsets.UTF_8) }.getOrNull() ?: ""
    }

    fun clear(name: String) {
        File(Paths.appData, name).delete()
    }
}
