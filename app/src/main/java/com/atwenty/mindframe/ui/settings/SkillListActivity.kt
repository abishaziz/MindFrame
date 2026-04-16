package com.atwenty.mindframe.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import com.atwenty.mindframe.MindFrameApp
import com.atwenty.mindframe.R
import com.atwenty.mindframe.skills.registry.SkillRegistry

class SkillListActivity : AppCompatActivity() {

    private lateinit var skillRegistry: SkillRegistry
    private lateinit var containerLearned: LinearLayout
    private lateinit var containerSystem: LinearLayout
    private lateinit var tvNoLearned: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skill_list)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val app = application as MindFrameApp
        skillRegistry = app.skillRegistry

        // Bind views
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        containerLearned = findViewById(R.id.container_learned_skills)
        containerSystem = findViewById(R.id.container_system_skills)
        tvNoLearned = findViewById(R.id.tv_no_learned_skills)

        loadSkills()
    }

    private fun loadSkills() {
        loadLearnedSkills()
        loadSystemSkills()
    }

    private fun loadLearnedSkills() {
        val skills = skillRegistry.getLearnedRecipes()
        containerLearned.removeAllViews()

        if (skills.isEmpty()) {
            tvNoLearned.visibility = View.VISIBLE
        } else {
            tvNoLearned.visibility = View.GONE
            for (skill in skills) {
                val view = LayoutInflater.from(this).inflate(R.layout.item_learned_skill, containerLearned, false)
                view.findViewById<TextView>(R.id.tv_skill_name).text = skill.name
                
                view.findViewById<View>(R.id.btn_delete_skill).setOnClickListener {
                    showDeleteConfirmation(skill)
                }
                containerLearned.addView(view)
            }
        }
    }

    private fun loadSystemSkills() {
        val skills = skillRegistry.getSystemRecipes()
        containerSystem.removeAllViews()

        for (skill in skills) {
            val view = LayoutInflater.from(this).inflate(R.layout.item_learned_skill, containerSystem, false)
            view.findViewById<TextView>(R.id.tv_skill_name).text = skill.name
            
            // Hide delete button for system skills
            view.findViewById<View>(R.id.btn_delete_skill).visibility = View.GONE
            
            // Optional: change icon for system skills
            // view.findViewById<ImageView>(R.id.iv_skill_icon).setImageResource(R.drawable.ic_system)
            
            containerSystem.addView(view)
        }
    }

    private fun showDeleteConfirmation(skill: SkillRegistry.SkillRecipe) {
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
}
