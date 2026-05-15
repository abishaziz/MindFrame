package com.atwenty.mindframe.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.atwenty.mindframe.MindFrameApp
import com.atwenty.mindframe.R
import com.atwenty.mindframe.data.local.SettingsRepository
import com.atwenty.mindframe.skills.registry.SkillRegistry
import com.atwenty.mindframe.ui.onboarding.PermissionActivity
import com.atwenty.mindframe.domain.entities.ProviderType
import com.atwenty.mindframe.data.local.SettingsConstants
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.TextView
import android.view.View

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRegistry: SkillRegistry
    
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etModelName: TextInputEditText
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var switchMemoryAware: SwitchMaterial
    private lateinit var switchDevMode: SwitchMaterial
    private lateinit var tvCurrentTheme: TextView
    private lateinit var tvCurrentProvider: TextView
    
    private lateinit var currentProvider: ProviderType

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val app = application as MindFrameApp
        settingsRepository = app.settingsRepository
        skillRegistry = app.skillRegistry

        // Bind views
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        
        etApiKey = findViewById(R.id.et_api_key)
        etBaseUrl = findViewById(R.id.et_base_url)
        etModelName = findViewById(R.id.et_model_name)
        tilApiKey = findViewById(R.id.til_api_key)
        switchNotifications = findViewById(R.id.switch_notifications)
        switchMemoryAware = findViewById(R.id.switch_memory_aware)
        switchDevMode = findViewById(R.id.switch_dev_mode)
        findViewById<View>(R.id.btn_manage_skills).setOnClickListener {
            startActivity(Intent(this, SkillListActivity::class.java))
        }

        // Load current values
        currentProvider = settingsRepository.activeProvider
        tvCurrentProvider = findViewById(R.id.tv_current_provider)
        loadProviderSettings(currentProvider)
        
        switchNotifications.isChecked = settingsRepository.isNotificationReadingEnabled
        switchMemoryAware.isChecked = settingsRepository.isMemoryAware
        switchDevMode.isChecked = settingsRepository.isDeveloperMode
        
        tvCurrentTheme = findViewById(R.id.tv_current_theme)
        updateThemeSummary()

        findViewById<View>(R.id.btn_manage_theme).setOnClickListener {
            showThemeSelectionDialog()
        }
        
        findViewById<View>(R.id.btn_manage_provider).setOnClickListener {
            showProviderSelectionDialog()
        }

        // Dynamic Header and Skill Visibility based on Dev Mode
        val tvSkillsHeader = findViewById<TextView>(R.id.tv_skills_header)
        val btnManageSkills = findViewById<View>(R.id.btn_manage_skills)
        val skillDivider = findViewById<View>(R.id.skill_management_divider)

        val updateSkillsVisibility = { isEnabled: Boolean ->
            val visibility = if (isEnabled) View.VISIBLE else View.GONE
            btnManageSkills.visibility = visibility
            skillDivider.visibility = visibility
            tvSkillsHeader.text = if (isEnabled) "SKILLS & PERMISSIONS" else "PERMISSIONS"
        }

        updateSkillsVisibility(switchDevMode.isChecked)
        switchDevMode.setOnCheckedChangeListener { _, isChecked ->
            updateSkillsVisibility(isChecked)
        }

        // Buttons
        findViewById<android.view.View>(R.id.btn_permissions).setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val baseUrl = etBaseUrl.text?.toString()?.trim() ?: ""
        if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http")) {
            Toast.makeText(this, "Base URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
            return
        }
        
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        val model = etModelName.text?.toString()?.trim() ?: ""

        when (currentProvider) {
            ProviderType.OLLAMA_CLOUD -> {
                settingsRepository.ollamaCloudApiKey = apiKey
                settingsRepository.ollamaCloudBaseUrl = baseUrl.ifEmpty { SettingsConstants.DEFAULT_OLLAMA_CLOUD_BASE_URL }
                settingsRepository.ollamaCloudModel = model.ifEmpty { SettingsConstants.DEFAULT_OLLAMA_CLOUD_MODEL }
            }
            ProviderType.OPENROUTER -> {
                settingsRepository.openRouterApiKey = apiKey
                settingsRepository.openRouterBaseUrl = baseUrl.ifEmpty { SettingsConstants.DEFAULT_OPENROUTER_BASE_URL }
                settingsRepository.openRouterModel = model.ifEmpty { SettingsConstants.DEFAULT_OPENROUTER_MODEL }
            }
        }
        
        settingsRepository.activeProvider = currentProvider
        settingsRepository.isNotificationReadingEnabled = switchNotifications.isChecked
        settingsRepository.isMemoryAware = switchMemoryAware.isChecked
        settingsRepository.isDeveloperMode = switchDevMode.isChecked

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateThemeSummary() {
        val themeNames = arrayOf("System Default", "Light Mode", "Dark Mode")
        tvCurrentTheme.text = themeNames[settingsRepository.themeMode]
    }

    private fun loadProviderSettings(provider: ProviderType) {
        currentProvider = provider
        when (provider) {
            ProviderType.OLLAMA_CLOUD -> {
                tvCurrentProvider.text = "Ollama Cloud API"
                tilApiKey.hint = "Ollama Cloud API Key"
                etApiKey.setText(settingsRepository.ollamaCloudApiKey)
                etBaseUrl.setText(settingsRepository.ollamaCloudBaseUrl)
                etModelName.setText(settingsRepository.ollamaCloudModel)
            }
            ProviderType.OPENROUTER -> {
                tvCurrentProvider.text = "OpenRouter API"
                tilApiKey.hint = "OpenRouter API Key"
                etApiKey.setText(settingsRepository.openRouterApiKey)
                etBaseUrl.setText(settingsRepository.openRouterBaseUrl)
                etModelName.setText(settingsRepository.openRouterModel)
            }
        }
    }

    private fun showProviderSelectionDialog() {
        val options = arrayOf("Ollama Cloud API", "OpenRouter API")
        val current = if (currentProvider == ProviderType.OLLAMA_CLOUD) 0 else 1

        MaterialAlertDialogBuilder(this)
            .setTitle("Select LLM Provider")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val selectedProvider = if (which == 0) ProviderType.OLLAMA_CLOUD else ProviderType.OPENROUTER
                
                // If they changed it, save the current fields to the old provider before switching
                if (selectedProvider != currentProvider) {
                    saveSettingsSilent() 
                }
                
                loadProviderSettings(selectedProvider)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveSettingsSilent() {
        val baseUrl = etBaseUrl.text?.toString()?.trim() ?: ""
        val apiKey = etApiKey.text?.toString()?.trim() ?: ""
        val model = etModelName.text?.toString()?.trim() ?: ""

        when (currentProvider) {
            ProviderType.OLLAMA_CLOUD -> {
                settingsRepository.ollamaCloudApiKey = apiKey
                settingsRepository.ollamaCloudBaseUrl = baseUrl.ifEmpty { SettingsConstants.DEFAULT_OLLAMA_CLOUD_BASE_URL }
                settingsRepository.ollamaCloudModel = model.ifEmpty { SettingsConstants.DEFAULT_OLLAMA_CLOUD_MODEL }
            }
            ProviderType.OPENROUTER -> {
                settingsRepository.openRouterApiKey = apiKey
                settingsRepository.openRouterBaseUrl = baseUrl.ifEmpty { SettingsConstants.DEFAULT_OPENROUTER_BASE_URL }
                settingsRepository.openRouterModel = model.ifEmpty { SettingsConstants.DEFAULT_OPENROUTER_MODEL }
            }
        }
    }

    private fun showThemeSelectionDialog() {
        val options = arrayOf("System Default", "Light Mode", "Dark Mode")
        val current = settingsRepository.themeMode

        MaterialAlertDialogBuilder(this)
            .setTitle("Theme Selection")
            .setSingleChoiceItems(options, current) { dialog, which ->
                settingsRepository.themeMode = which
                updateThemeSummary()
                
                // Apply theme immediately
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                
                dialog.dismiss()
                // Activity will recreate to apply theme
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
