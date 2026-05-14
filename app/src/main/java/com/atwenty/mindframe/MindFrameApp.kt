package com.atwenty.mindframe

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.atwenty.mindframe.data.local.SettingsRepository
import com.atwenty.mindframe.data.remote.OllamaCloudProvider
import com.atwenty.mindframe.data.remote.OpenRouterProvider
import com.atwenty.mindframe.domain.model.ModelProvider
import com.atwenty.mindframe.domain.model.ProviderType
import com.atwenty.mindframe.domain.usecase.AgentOrchestrator
import com.atwenty.mindframe.skills.SkillGenerator
import com.atwenty.mindframe.skills.registry.SkillRegistry

class MindFrameApp : Application() {
    
    val settingsRepository by lazy { SettingsRepository(this) }
    private val ollamaProvider by lazy { OllamaCloudProvider(settingsRepository) }
    private val openRouterProvider by lazy { OpenRouterProvider(settingsRepository) }

    val modelProvider: ModelProvider
        get() = when (settingsRepository.activeProvider) {
            ProviderType.OPENROUTER -> openRouterProvider
            ProviderType.OLLAMA -> ollamaProvider
        }
    val skillRegistry by lazy { SkillRegistry(this) }
    val skillGenerator by lazy { SkillGenerator({ modelProvider }, skillRegistry) }
    val orchestrator by lazy { AgentOrchestrator({ modelProvider }, skillRegistry, skillGenerator, settingsRepository) }
    
    override fun onCreate() {
        super.onCreate()
        
        // Apply theme preference immediately
        val mode = when (settingsRepository.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO     // Light
            2 -> AppCompatDelegate.MODE_NIGHT_YES    // Dark
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
