package com.backuper.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages encrypted storage of the Google Drive OAuth token using Android Keystore.
 */
object EncryptionManager {
    private const val PREFS_NAME = "secure_prefs"
    private const val TOKEN_KEY = "drive_token"

    private fun getSharedPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(context: Context, token: String) {
        getSharedPrefs(context).edit().putString(TOKEN_KEY, token).apply()
    }

    fun getToken(context: Context): String? {
        return getSharedPrefs(context).getString(TOKEN_KEY, null)
    }

    fun clearToken(context: Context) {
        getSharedPrefs(context).edit().remove(TOKEN_KEY).apply()
    }
}
