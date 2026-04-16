package com.atwenty.personalagent.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.atwenty.personalagent.PersonalAgentApp
import com.atwenty.personalagent.R
import com.atwenty.personalagent.data.local.SettingsRepository
import com.atwenty.personalagent.skills.registry.SkillRegistry
import com.atwenty.personalagent.ui.onboarding.PermissionActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRegistry: SkillRegistry
    
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etModelName: TextInputEditText
    private lateinit var switchNotifications: SwitchMaterial
    private lateinit var switchDevMode: SwitchMaterial
    private lateinit var containerSkills: LinearLayout
    private lateinit var tvNoSkills: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val app = application as PersonalAgentApp
        settingsRepository = app.settingsRepository
        skillRegistry = app.skillRegistry

        // Bind views
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        
        etApiKey = findViewById(R.id.et_api_key)
        etBaseUrl = findViewById(R.id.et_base_url)
        etModelName = findViewById(R.id.et_model_name)
        switchNotifications = findViewById(R.id.switch_notifications)
        switchDevMode = findViewById(R.id.switch_dev_mode)
        containerSkills = findViewById(R.id.container_skills)
        tvNoSkills = findViewById(R.id.tv_no_skills)

        // Load current values
        etApiKey.setText(settingsRepository.ollamaApiKey)
        etBaseUrl.setText(settingsRepository.ollamaBaseUrl)
        etModelName.setText(settingsRepository.modelName)
        switchNotifications.isChecked = settingsRepository.isNotificationReadingEnabled
        switchDevMode.isChecked = settingsRepository.isDeveloperMode

        // Buttons
        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            saveSettings()
        }

        loadLearnedSkills()
    }

    private fun saveSettings() {
        val baseUrl = etBaseUrl.text?.toString()?.trim() ?: ""
        if (baseUrl.isNotEmpty() && !baseUrl.startsWith("http")) {
            Toast.makeText(this, "Base URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
            return
        }

        settingsRepository.ollamaApiKey = etApiKey.text?.toString()?.trim() ?: ""
        settingsRepository.ollamaBaseUrl = baseUrl.ifEmpty { SettingsRepository.DEFAULT_BASE_URL }
        settingsRepository.modelName = etModelName.text?.toString()?.trim()?.ifEmpty { SettingsRepository.DEFAULT_MODEL } ?: SettingsRepository.DEFAULT_MODEL
        settingsRepository.isNotificationReadingEnabled = switchNotifications.isChecked
        settingsRepository.isDeveloperMode = switchDevMode.isChecked

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun loadLearnedSkills() {
        val learnedSkills = skillRegistry.getLearnedRecipes()
        
        containerSkills.removeAllViews()
        
        if (learnedSkills.isEmpty()) {
            tvNoSkills.visibility = android.view.View.VISIBLE
            return
        }
        
        tvNoSkills.visibility = android.view.View.GONE
        
        for (skill in learnedSkills) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_learned_skill, containerSkills, false)
            
            view.findViewById<TextView>(R.id.tv_skill_name).text = skill.name
            
            view.findViewById<android.view.View>(R.id.btn_delete_skill).setOnClickListener {
                // Show confirmation dialog before deleting
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete Skill")
                    .setMessage("Are you sure you want to delete '${skill.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        skill.fileName?.let { fileName ->
                            if (skillRegistry.deleteLearnedRecipe(fileName)) {
                                loadLearnedSkills()
                                Toast.makeText(this, "Skill deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            
            containerSkills.addView(view)
        }
    }
}
