package com.atwenty.mindframe

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.atwenty.mindframe.data.local.SettingsRepository
import com.atwenty.mindframe.llm.ollamacloud.OllamaCloudProvider
import com.atwenty.mindframe.llm.openrouter.OpenRouterProvider
import com.atwenty.mindframe.domain.entities.LlmProvider
import com.atwenty.mindframe.domain.entities.ProviderType
import com.atwenty.mindframe.domain.usecase.AgentOrchestrator
import com.atwenty.mindframe.skills.SkillGenerator
import com.atwenty.mindframe.skills.registry.SkillRegistry

class MindFrameApp : Application() {
    
    val settingsRepository by lazy { SettingsRepository(this) }
    private val ollamaCloudProvider by lazy { OllamaCloudProvider(settingsRepository) }
    private val openRouterProvider by lazy { OpenRouterProvider(settingsRepository) }

    val LlmProvider: LlmProvider
        get() = when (settingsRepository.activeProvider) {
            ProviderType.OPENROUTER -> openRouterProvider
            ProviderType.OLLAMA_CLOUD -> ollamaCloudProvider
        }
    val skillRegistry by lazy { SkillRegistry(this) }
    val skillGenerator by lazy { SkillGenerator({ LlmProvider }, skillRegistry) }
    val orchestrator by lazy { AgentOrchestrator({ LlmProvider }, skillRegistry, skillGenerator, settingsRepository) }
    
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
