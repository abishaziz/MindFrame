package com.atwenty.mindframe.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.atwenty.mindframe.domain.entities.ProviderType
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class SettingsRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SettingsConstants.SECURE_PREFS_NAME,
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

    // --- Property Delegates ---

    private fun stringPref(key: String, defaultValue: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = prefs.getString(key, defaultValue) ?: defaultValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = prefs.edit().putString(key, value).apply()
    }

    private fun secureStringPref(key: String, defaultValue: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = securePrefs.getString(key, defaultValue) ?: defaultValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = securePrefs.edit().putString(key, value).apply()
    }

    private fun booleanPref(key: String, defaultValue: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = prefs.getBoolean(key, defaultValue)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    private fun intPref(key: String, defaultValue: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = prefs.getInt(key, defaultValue)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = prefs.edit().putInt(key, value).apply()
    }

    private fun stringSetPref(key: String, defaultValue: Set<String>) = object : ReadWriteProperty<Any?, Set<String>> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Set<String> = prefs.getStringSet(key, defaultValue) ?: defaultValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<String>) = prefs.edit().putStringSet(key, value).apply()
    }

    private fun providerTypePref(key: String, defaultValue: ProviderType) = object : ReadWriteProperty<Any?, ProviderType> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): ProviderType {
            val name = prefs.getString(key, defaultValue.name) ?: defaultValue.name
            return try { ProviderType.valueOf(name) } catch (e: Exception) { defaultValue }
        }
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: ProviderType) {
            prefs.edit().putString(key, value.name).apply()
        }
    }

    // --- Properties ---

    var activeProvider by providerTypePref(SettingsConstants.KEY_ACTIVE_PROVIDER, ProviderType.OLLAMA_CLOUD)
    
    var ollamaCloudApiKey by secureStringPref(SettingsConstants.KEY_OLLAMA_CLOUD_API_KEY, "")
    var openRouterApiKey by secureStringPref(SettingsConstants.KEY_OPENROUTER_API_KEY, "")
    
    var ollamaCloudModel by stringPref(SettingsConstants.KEY_OLLAMA_CLOUD_MODEL, SettingsConstants.DEFAULT_OLLAMA_CLOUD_MODEL)
    var openRouterModel by stringPref(SettingsConstants.KEY_OPENROUTER_MODEL, SettingsConstants.DEFAULT_OPENROUTER_MODEL)
    
    var ollamaCloudBaseUrl by stringPref(SettingsConstants.KEY_OLLAMA_CLOUD_BASE_URL, SettingsConstants.DEFAULT_OLLAMA_CLOUD_BASE_URL)
    var openRouterBaseUrl by stringPref(SettingsConstants.KEY_OPENROUTER_BASE_URL, SettingsConstants.DEFAULT_OPENROUTER_BASE_URL)
    
    var isNotificationReadingEnabled by booleanPref(SettingsConstants.KEY_NOTIFICATION_READING, false)
    var isDeveloperMode by booleanPref(SettingsConstants.KEY_DEVELOPER_MODE, false)
    var isMemoryAware by booleanPref(SettingsConstants.KEY_MEMORY_AWARE, true)
    
    var themeMode by intPref(SettingsConstants.KEY_THEME_MODE, 0)
    
    var blacklistedPackages by stringSetPref(SettingsConstants.KEY_BLACKLIST, SettingsConstants.DEFAULT_BLACKLIST)

    // --- Blacklist Helpers ---

    fun addBlacklistedPackage(packageName: String) {
        blacklistedPackages = blacklistedPackages + packageName
    }

    fun removeBlacklistedPackage(packageName: String) {
        blacklistedPackages = blacklistedPackages - packageName
    }

    fun isPackageBlacklisted(packageName: String): Boolean {
        return packageName in blacklistedPackages
    }
}
