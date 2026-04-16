package com.atwenty.personalagent.skills.registry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import com.atwenty.personalagent.domain.model.OllamaFunction
import com.atwenty.personalagent.domain.model.OllamaParameters
import com.atwenty.personalagent.domain.model.OllamaProperty
import com.atwenty.personalagent.domain.model.OllamaTool
import com.atwenty.personalagent.service.accessibility.AccessibilityDriver
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

class SkillRegistry(private val context: Context) {

    companion object {
        private const val TAG = "PA_SkillRegistry"
        private const val SYSTEM_SKILLS_DIR = "skills"
        private const val LEARNED_SKILLS_DIR = "learned_skills"
    }

    private val gson = Gson()
    private val systemRecipes = mutableListOf<SkillRecipe>()
    private val learnedRecipes = mutableListOf<SkillRecipe>()

    init {
        loadSystemRecipes()
        loadLearnedRecipes()
    }

    fun getAvailableTools(): List<OllamaTool> {
        return listOf(
            createTool(
                "open_app", 
                "Opens a mobile application by its package name.",
                mapOf("packageName" to OllamaProperty("string", "The Android package name of the app.")),
                listOf("packageName")
            ),
            createTool(
                "click_node",
                "Clicks on a UI element using its ID from the screen capture.",
                mapOf("nodeId" to OllamaProperty("string", "The unique ID of the node to click.")),
                listOf("nodeId")
            ),
            createTool(
                "type_text",
                "Types text into an editable UI element.",
                mapOf(
                    "nodeId" to OllamaProperty("string", "The ID of the text field."),
                    "text" to OllamaProperty("string", "The text to enter.")
                ),
                listOf("nodeId", "text")
            ),
            createTool(
                "scroll",
                "Scrolls the screen in a specified direction.",
                mapOf("direction" to OllamaProperty("string", "up, down, left, or right", listOf("up", "down", "left", "right"))),
                listOf("direction")
            ),
            createTool(
                "go_back",
                "Performs the back button action.",
                emptyMap(),
                emptyList()
            ),
            createTool(
                "go_home",
                "Navigates to the home screen.",
                emptyMap(),
                emptyList()
            ),
            createTool(
                "search_web",
                "Searches the web for information using DuckDuckGo.",
                mapOf("query" to OllamaProperty("string", "The search query.")),
                listOf("query")
            ),
            createTool(
                "set_alarm",
                "Sets a system alarm for a specific time.",
                mapOf(
                    "hour" to OllamaProperty("integer", "0-23"),
                    "minute" to OllamaProperty("integer", "0-59"),
                    "message" to OllamaProperty("string", "Label for the alarm")
                ),
                listOf("hour", "minute")
            ),
            createTool(
                "open_deep_link",
                "Opens a URL or deep link in the appropriate app.",
                mapOf("uri" to OllamaProperty("string", "The URI to open.")),
                listOf("uri")
            ),
            createTool(
                "wait",
                "Waits for a few seconds for the UI to settle.",
                mapOf("seconds" to OllamaProperty("integer", "Time to wait")),
                listOf("seconds")
            ),
            createTool(
                "report_success",
                "Call this when the user's task is fully completed. Provide a summary of what was done.",
                mapOf("message" to OllamaProperty("string", "A final summary of the completed task.")),
                listOf("message")
            ),
            createTool(
                "report_error",
                "Call this if you are stuck or encountered a terminal error that prevents you from finishing.",
                mapOf("reason" to OllamaProperty("string", "The reason why the task failed.")),
                listOf("reason")
            ),
            createTool(
                "ask_user_question",
                "Call this if you need the user to provide information, make a choice, or perform an action you cannot do.",
                mapOf("question" to OllamaProperty("string", "The question or instruction for the user.")),
                listOf("question")
            ),
            createTool(
                "get_installed_apps",
                "Returns a list of all user-facing applications installed on the device and their package names.",
                emptyMap(),
                emptyList()
            ),
            createTool(
                "started_skill",
                "Call this as the first action if you are following a known RECIPE. This signals your intent to the system.",
                mapOf("skill_name" to OllamaProperty("string", "The name of the recipe you are starting.")),
                listOf("skill_name")
            )
        )
    }

    fun getRecipesForPrompt(): String {
        val allRecipes = systemRecipes + learnedRecipes
        if (allRecipes.isEmpty()) return "No high-level recipes available yet."
        
        return allRecipes.joinToString("\n\n") { recipe ->
            "### ${recipe.name}\n${recipe.description}\nSteps:\n${recipe.steps}"
        }
    }

    fun executeTool(name: String, args: Map<String, String>, driver: AccessibilityDriver): String {
        Log.i(TAG, "Executing tool: $name with args: $args")
        return when (name) {
            "open_app" -> executeOpenApp(args["packageName"] ?: "")
            "click_node" -> if (driver.clickNode(args["nodeId"] ?: "")) "Clicked successfully" else "Failed to click"
            "type_text" -> if (driver.typeText(args["text"] ?: "", args["nodeId"])) "Typed successfully" else "Failed to type"
            "scroll" -> if (driver.scroll(args["direction"] ?: "down")) "Scrolled successfully" else "Failed to scroll"
            "go_back" -> if (driver.pressBack()) "Went back" else "Failed to go back"
            "go_home" -> if (driver.pressHome()) "Went home" else "Failed to go home"
            "search_web" -> executeWebSearch(args["query"] ?: "")
            "set_alarm" -> executeSetAlarm(args)
            "open_deep_link" -> executeOpenUri(args["uri"] ?: "")
            "wait" -> {
                Thread.sleep((args["seconds"]?.toLongOrNull() ?: 2L) * 1000L)
                "Waited successfully"
            }
            "report_success" -> "TASK_COMPLETE: ${args["message"] ?: "Task finished"}"
            "report_error" -> "TASK_ERROR: ${args["reason"] ?: "Unknown error"}"
            "ask_user_question" -> "WAITING_FOR_USER: ${args["question"] ?: "How can I help you?"}"
            "get_installed_apps" -> executeGetInstalledApps()
            "started_skill" -> "SKILL_STARTED: ${args["skill_name"] ?: "Unknown"}"
            else -> "Unknown tool: $name"
        }
    }

    fun saveLearnedRecipe(name: String, content: String) {
        try {
            val dir = File(context.filesDir, LEARNED_SKILLS_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "$name.md").writeText(content)
            loadLearnedRecipes() // Refresh
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recipe", e)
        }
    }

    fun getLearnedRecipeNames(): List<String> {
        return learnedRecipes.map { it.name }
    }

    fun getSystemRecipeNames(): List<String> {
        return systemRecipes.map { it.name }
    }

    fun deleteLearnedRecipe(name: String): Boolean {
        try {
            val dir = File(context.filesDir, LEARNED_SKILLS_DIR)
            val file = File(dir, "$name.md")
            if (file.exists() && file.delete()) {
                loadLearnedRecipes()
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recipe", e)
        }
        return false
    }

    private fun loadSystemRecipes() {
        systemRecipes.clear()
        try {
            val assets = context.assets.list(SYSTEM_SKILLS_DIR) ?: return
            for (filename in assets) {
                if (filename.endsWith(".md")) {
                    val content = context.assets.open("$SYSTEM_SKILLS_DIR/$filename").bufferedReader().use { it.readText() }
                    parseRecipe(content)?.let { systemRecipes.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading system recipes", e)
        }
    }

    private fun loadLearnedRecipes() {
        learnedRecipes.clear()
        try {
            val dir = File(context.filesDir, LEARNED_SKILLS_DIR)
            if (!dir.exists()) return
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".md")) {
                    parseRecipe(file.readText())?.let { learnedRecipes.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading learned recipes", e)
        }
    }

    private fun executeGetInstalledApps(): String {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            val apps = pm.queryIntentActivities(mainIntent, 0)
            if (apps.isEmpty()) return "No launcher apps found."

            val appList = apps.joinToString("\n") { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                val pkg = resolveInfo.activityInfo.packageName
                "- $label: $pkg"
            }
            "Installed launcher applications:\n$appList"
        } catch (e: Exception) {
            "Failed to list apps: ${e.message}"
        }
    }

    private fun parseRecipe(content: String): SkillRecipe? {
        val lines = content.lines()
        val name = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: return null
        val descriptionStart = lines.indexOfFirst { it.startsWith("## Description") }
        val stepsStart = lines.indexOfFirst { it.startsWith("## Steps") }
        
        val description = if (descriptionStart != -1 && stepsStart != -1) {
            lines.subList(descriptionStart + 1, stepsStart).joinToString(" ").trim()
        } else ""
        
        val steps = if (stepsStart != -1) {
            lines.subList(stepsStart + 1, lines.size).joinToString("\n").trim()
        } else ""

        return SkillRecipe(name, description, steps, content)
    }

    private fun executeOpenApp(packageName: String): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened application: $packageName"
            } else {
                "Application not found: $packageName"
            }
        } catch (e: Exception) {
            "Error opening app: ${e.message}"
        }
    }

    private fun executeOpenUri(uri: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened URI: $uri"
        } catch (e: Exception) {
            "Error opening URI: ${e.message}"
        }
    }

    private fun executeWebSearch(query: String): String {
        return try {
            val url = "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json"
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            val jsonObj = gson.fromJson(body, JsonObject::class.java)
            val abstract = if (jsonObj.has("AbstractText")) jsonObj.get("AbstractText").asString else ""
            val answer = if (jsonObj.has("Answer")) jsonObj.get("Answer").asString else ""

            val result = when {
                answer.isNotBlank() -> answer
                abstract.isNotBlank() -> abstract
                else -> "No direct answer found. The user may need to search in a browser."
            }

            "Search results for '$query': $result"
        } catch (e: Exception) {
            "Web search failed: ${e.message}"
        }
    }

    private fun executeSetAlarm(args: Map<String, String>): String {
        return try {
            val hour = args["hour"]?.toIntOrNull() ?: return "Invalid hour"
            val minute = args["minute"]?.toIntOrNull() ?: return "Invalid minute"
            val message = args["message"] ?: "Alarm"

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Set alarm for $hour:${minute.toString().padStart(2, '0')} - $message"
        } catch (e: Exception) {
            "Failed to set alarm: ${e.message}"
        }
    }

    private fun createTool(
        name: String,
        description: String,
        properties: Map<String, OllamaProperty>,
        required: List<String>
    ): OllamaTool {
        return OllamaTool(
            function = OllamaFunction(
                name = name,
                description = description,
                parameters = OllamaParameters(
                    properties = properties,
                    required = required
                )
            )
        )
    }

    data class SkillRecipe(
        val name: String,
        val description: String,
        val steps: String,
        val rawContent: String
    )
}
