package com.rentgg.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Lưu token trong EncryptedSharedPreferences (khóa nằm trong Android Keystore). */
class TokenStore(context: Context) : TokenStorage {
    private val prefs: SharedPreferences = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
        .let { key ->
            EncryptedSharedPreferences.create(
                context,
                "rentgg_session",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    @Volatile private var cached: Pair<String, String>? = prefs.read()

    override val accessToken: String? get() = cached?.first
    override val refreshToken: String? get() = cached?.second
    override val hasSession: Boolean get() = cached != null

    @Synchronized
    override fun save(access: String, refresh: String) {
        cached = access to refresh
        prefs.edit().putString(ACCESS, access).putString(REFRESH, refresh).apply()
    }

    @Synchronized
    override fun clear() {
        cached = null
        prefs.edit().clear().apply()
    }

    private fun SharedPreferences.read(): Pair<String, String>? {
        val a = getString(ACCESS, null) ?: return null
        val r = getString(REFRESH, null) ?: return null
        return a to r
    }

    private companion object {
        const val ACCESS = "access"
        const val REFRESH = "refresh"
    }
}
