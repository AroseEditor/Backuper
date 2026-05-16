package com.backuper.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object EncryptionManager {
    private const val PREFS_NAME = "secure_prefs"
    private const val TOKEN_KEY = "gofile_token"

    private fun getSharedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if Keystore fails
            context.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun saveToken(context: Context, token: String) {
        getSharedPrefs(context).edit().putString(TOKEN_KEY, token).apply()
    }

    fun getToken(context: Context): String? {
        return getSharedPrefs(context).getString(TOKEN_KEY, null)
    }
}
