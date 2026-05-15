package com.atwenty.mindframe.data.local

object SettingsConstants {
    // --- SharedPreferences Keys ---
    const val PREFS_NAME = "mindframe_prefs"
    const val SECURE_PREFS_NAME = "mindframe_secure_prefs"

    const val KEY_ACTIVE_PROVIDER = "active_provider"
    const val KEY_OLLAMA_CLOUD_API_KEY = "ollama_cloud_api_key"
    const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    const val KEY_OLLAMA_CLOUD_MODEL = "ollama_cloud_model"
    const val KEY_OPENROUTER_MODEL = "openrouter_model"
    const val KEY_OLLAMA_CLOUD_BASE_URL = "ollama_cloud_base_url"
    const val KEY_OPENROUTER_BASE_URL = "openrouter_base_url"
    const val KEY_NOTIFICATION_READING = "notification_reading"
    const val KEY_DEVELOPER_MODE = "developer_mode"
    const val KEY_MEMORY_AWARE = "memory_aware"
    const val KEY_BLACKLIST = "privacy_blacklist"
    const val KEY_THEME_MODE = "theme_mode"

    // --- Default Values ---
    const val DEFAULT_OLLAMA_CLOUD_MODEL = "gemma4:31b-cloud"
    const val DEFAULT_OLLAMA_CLOUD_BASE_URL = "https://ollama.com"
    
    const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"
    const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

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
