package com.atwenty.personalagent

import android.app.Application
import com.atwenty.personalagent.data.local.SettingsRepository
import com.atwenty.personalagent.data.remote.OllamaCloudProvider
import com.atwenty.personalagent.domain.usecase.AgentOrchestrator
import com.atwenty.personalagent.skills.registry.SkillRegistry

class PersonalAgentApp : Application() {
    
    val settingsRepository by lazy { SettingsRepository(this) }
    val ollamaProvider by lazy { OllamaCloudProvider(settingsRepository) }
    val skillRegistry by lazy { SkillRegistry(this) }
    val orchestrator by lazy { AgentOrchestrator(ollamaProvider, skillRegistry) }
    
    override fun onCreate() {
        super.onCreate()
        // Initialize any necessary subsystems here
    }
}
