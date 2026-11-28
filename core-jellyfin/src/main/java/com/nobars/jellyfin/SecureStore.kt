package com.nobars.jellyfin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The remembered login. The server address and user are kept as they are; the access token is encrypted with a key
 * that never leaves the phone's keystore (AES-GCM), so a copy of the app's files is not enough to use it.
 */
internal class SecureStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("nb_session", Context.MODE_PRIVATE)

    fun save(s: NbJellyfin.Session) {
        prefs
            .edit()
            .putString("server", s.serverUrl)
            .putString("serverName", s.serverName)
            .putString("userId", s.userId.toString())
            .putString("userName", s.userName)
            .putString("token", encrypt(s.token))
            .apply()
    }

    fun load(): NbJellyfin.Session? =
        try {
            val token = prefs.getString("token", null)?.let { decrypt(it) } ?: return null
            NbJellyfin.Session(
                serverUrl = prefs.getString("server", null) ?: return null,
                serverName = prefs.getString("serverName", null).orEmpty(),
                userId = UUID.fromString(prefs.getString("userId", null) ?: return null),
                userName = prefs.getString("userName", null).orEmpty(),
                token = token,
            )
        } catch (e: Exception) {
            Timber.w(e, "NoBars: saved login unreadable")
            null
        }

    fun clear() = prefs.edit().clear().apply()

    /** The last server address used, kept after signing out so it is offered again. */
    fun lastServer(): String? = prefs.getString("lastServer", null)

    fun rememberServer(url: String) = prefs.edit().putString("lastServer", url).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(text.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(c.doFinal(all.copyOfRange(12, all.size)))
    }

    private companion object {
        const val ALIAS = "nobars_token"
    }
}
