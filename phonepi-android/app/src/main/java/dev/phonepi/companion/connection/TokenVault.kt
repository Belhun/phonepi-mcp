package dev.phonepi.companion.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed AES-GCM store for the WebSocket token.
 * One-time migration from the old SharedPreferences plaintext value so an
 * already-paired phone keeps working. JVM unit tests fall back to private prefs.
 */
object TokenVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "phonepi_ws_token_aes"
    private const val PREFS = "phonepi_secure_tokens"
    private const val KEY_LAST = "last"
    private const val MIGRATED = "migrated_plaintext_v1"
    private const val TAG = "PhonePiTokenVault"
    private const val GCM_TAG_BITS = 128

    fun lastToken(context: Context): String = get(context, KEY_LAST)

    fun setLastToken(context: Context, token: String) {
        put(context, KEY_LAST, token)
    }

    fun serverToken(context: Context, serverId: String): String = get(context, "server:$serverId")

    fun setServerToken(context: Context, serverId: String, token: String) {
        put(context, "server:$serverId", token)
    }

    fun migrateIfNeeded(context: Context) {
        val app = context.applicationContext
        val secure = prefs(app)
        if (secure.getBoolean(MIGRATED, false)) return
        synchronized(this) {
            if (secure.getBoolean(MIGRATED, false)) return
            val legacy = app.getSharedPreferences("phonepi_connection", Context.MODE_PRIVATE)
            val old = legacy.getString("last_ws_token", null).orEmpty()
            if (old.isNotBlank() && lastToken(app).isBlank()) {
                setLastToken(app, old)
            }
            legacy.edit().remove("last_ws_token").apply()
            val servers = ServerList.decode(
                app.getSharedPreferences("phonepi_servers", Context.MODE_PRIVATE)
                    .getString("servers_json", null),
            ).orEmpty()
            var changed = false
            val stripped = servers.map { entry ->
                if (entry.token.isNotBlank()) {
                    setServerToken(app, entry.id, entry.token)
                    changed = true
                    entry.copy(token = "")
                } else {
                    entry
                }
            }
            if (changed) {
                app.getSharedPreferences("phonepi_servers", Context.MODE_PRIVATE)
                    .edit()
                    .putString("servers_json", ServerList.encode(stripped))
                    .apply()
            }
            secure.edit().putBoolean(MIGRATED, true).apply()
        }
    }

    fun get(context: Context, key: String): String {
        val app = context.applicationContext
        val packed = prefs(app).getString(cipherKey(key), null) ?: return fallbackGet(app, key)
        val secret = secretKey() ?: return fallbackGet(app, key)
        return runCatching {
            val blob = Base64.decode(packed, Base64.NO_WRAP)
            if (blob.size < 13) return fallbackGet(app, key)
            val iv = blob.copyOfRange(0, 12)
            val cipherBytes = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrElse {
            fallbackGet(app, key)
        }
    }

    fun put(context: Context, key: String, value: String) {
        val app = context.applicationContext
        val trimmed = value.trim()
        val secret = secretKey()
        if (secret == null) {
            fallbackPut(app, key, trimmed)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
            prefs(app).edit()
                .putString(cipherKey(key), packed)
                .remove(plainKey(key))
                .apply()
        }.onFailure {
            Log.w(TAG, "keystore write failed, using private prefs")
            fallbackPut(app, key, trimmed)
        }
    }

    private fun secretKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            gen.generateKey()
        }.getOrNull()
    }

    private fun fallbackGet(context: Context, key: String): String =
        prefs(context).getString(plainKey(key), "").orEmpty()

    private fun fallbackPut(context: Context, key: String, value: String) {
        prefs(context).edit().putString(plainKey(key), value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cipherKey(key: String) = "cipher:$key"
    private fun plainKey(key: String) = "plain:$key"
}
