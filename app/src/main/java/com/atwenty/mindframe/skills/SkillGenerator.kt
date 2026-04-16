package com.atwenty.mindframe.skills

import android.util.Log
import com.atwenty.mindframe.data.remote.OllamaCloudProvider
import com.atwenty.mindframe.domain.model.OllamaMessage
import com.atwenty.mindframe.domain.model.SessionLog
import com.atwenty.mindframe.skills.registry.SkillRegistry

/**
 * Generates SKILL.md files from successful session logs using the LLM.
 */
class SkillGenerator(
    private val ollamaProvider: OllamaCloudProvider,
    private val skillRegistry: SkillRegistry
) {
    companion object {
        private const val TAG = "MF_SkillGen"
    }

    suspend fun generateFromSession(sessionLog: SessionLog) {
        if (sessionLog.steps.isEmpty()) return

        val prompt = buildPrompt(sessionLog)

        val messages = listOf(
            OllamaMessage(role = "system", content = SKILL_SYNTHESIS_PROMPT),
            OllamaMessage(role = "user", content = prompt)
        )

        val response = ollamaProvider.sendMessage(messages, tools = null)

        // Extract and save the generated SKILL.md
        val skillContent = response.rawContent.trim()
        if (skillContent.isNotBlank() && skillContent.contains("#")) {
            val skillName = extractSkillName(skillContent, sessionLog.taskDescription)
            skillRegistry.saveLearnedRecipe(skillName, skillContent)
            Log.i(TAG, "Generated and saved skill: $skillName")
        } else {
            Log.w(TAG, "LLM did not generate a valid SKILL.md")
        }
    }

    private fun buildPrompt(sessionLog: SessionLog): String {
        val sb = StringBuilder()
        sb.appendLine("## Task Description")
        sb.appendLine(sessionLog.taskDescription)
        sb.appendLine()
        sb.appendLine("## Steps Taken (${sessionLog.steps.size} steps)")

        sessionLog.steps.forEach { step ->
            sb.appendLine("### Step ${step.stepNumber}")
            sb.appendLine("**Thought**: ${step.llmThought}")
            if (step.toolCall != null) {
                sb.appendLine("**Action**: ${step.toolCall.name}(${step.toolCall.arguments})")
            }
            sb.appendLine("**Result**: ${step.result}")
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun extractSkillName(content: String, defaultName: String): String {
        // Try to extract name from the first heading
        val match = Regex("^#\\s+(.+)", RegexOption.MULTILINE).find(content)
        val name = match?.groupValues?.get(1)?.trim() ?: defaultName
        // Sanitize for filename
        return name.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "_")
            .take(50)
    }

    private val SKILL_SYNTHESIS_PROMPT = """
You are a skill documentation generator. Given a successful task execution log, create a SKILL.md document that captures the strategy used.

Format your output as a Markdown document with:
# [Skill Name]

## Description
Brief description of what this skill does.

## Steps
1. Step-by-step instructions using the available tools.
2. Include which tools to use and in what order.
3. Include any important details about UI element selection.

## Notes
- Any edge cases or important observations.

Keep it concise and focused on the reusable pattern, not the specific instance.
    """.trimIndent()
}
