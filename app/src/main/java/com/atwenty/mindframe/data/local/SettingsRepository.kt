package com.atwenty.mindframe.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("mindframe_prefs", Context.MODE_PRIVATE)
    }

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "mindframe_secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if encryption is unavailable
            prefs
        }
    }

    // --- API Key (Encrypted) ---
    var ollamaApiKey: String
        get() = securePrefs.getString(KEY_OLLAMA_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_OLLAMA_API_KEY, value).apply()

    // --- Model Name ---
    var modelName: String
        get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL_NAME, value).apply()

    // --- Ollama Base URL ---
    var ollamaBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    // --- Notification Reading Toggle ---
    var isNotificationReadingEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_READING, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_READING, value).apply()

    // --- Developer Mode ---
    var isDeveloperMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER_MODE, value).apply()

    // --- First Launch ---
    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    // --- Theme Mode (0: System, 1: Light, 2: Dark) ---
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    // --- Privacy Blacklist ---
    var blacklistedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLIST, DEFAULT_BLACKLIST) ?: DEFAULT_BLACKLIST
        set(value) = prefs.edit().putStringSet(KEY_BLACKLIST, value).apply()

    fun addBlacklistedPackage(packageName: String) {
        blacklistedPackages = blacklistedPackages + packageName
    }

    fun removeBlacklistedPackage(packageName: String) {
        blacklistedPackages = blacklistedPackages - packageName
    }

    fun isPackageBlacklisted(packageName: String): Boolean {
        return packageName in blacklistedPackages
    }

    companion object {
        private const val KEY_OLLAMA_API_KEY = "ollama_api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_BASE_URL = "ollama_base_url"
        private const val KEY_NOTIFICATION_READING = "notification_reading"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_BLACKLIST = "privacy_blacklist"
        private const val KEY_THEME_MODE = "theme_mode"

        const val DEFAULT_MODEL = "gemma4:31b-cloud"
        const val DEFAULT_BASE_URL = "https://ollama.com"

        val DEFAULT_BLACKLIST = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "net.one97.paytm",                        // Paytm
            "com.phonepe.app",                        // PhonePe
            "com.csam.icici.bank.imobile",             // ICICI
            "com.sbi.lotusintouch",                    // SBI
            "com.axis.mobile",                         // Axis Bank
            "com.msf.kbank.mobile",                    // Kotak
        )
    }
}
