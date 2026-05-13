package com.atwenty.mindframe.domain.usecase

import android.content.Context
import android.util.Log
import com.atwenty.mindframe.data.remote.OllamaCloudProvider
import com.atwenty.mindframe.domain.model.*
import com.atwenty.mindframe.service.accessibility.AccessibilityDriver
import com.atwenty.mindframe.service.accessibility.MindFrameAccessibilityService
import com.atwenty.mindframe.skills.registry.SkillRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentOrchestrator(
    private val ollamaProvider: OllamaCloudProvider,
    private val skillRegistry: SkillRegistry,
    private val skillGenerator: com.atwenty.mindframe.skills.SkillGenerator,
    private val settingsRepository: com.atwenty.mindframe.data.local.SettingsRepository
) {
    companion object {
        private const val TAG = "MF_Orchestrator"
        private const val MAX_STEPS = 15
        private const val SCREEN_CHANGE_TIMEOUT_MS = 2000L
        private const val OVERLAY_HIDE_DELAY_MS = 100L
    }

    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val _chatMessages = MutableSharedFlow<ChatMessage>(replay = 10)
    val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()

    private var currentJob: Job? = null
    private var conversationHistory = mutableListOf<OllamaMessage>()
    private var currentSessionLog: SessionLog? = null
    private var currentSkillType: SkillType = SkillType.NEW
    private var sessionActive = false

    // System prompt, loaded once and cached
    private var systemPrompt: String? = null

    // Reference to overlay hide/show callbacks
    var onHideOverlay: (() -> Unit)? = null
    var onShowOverlay: (() -> Unit)? = null
    // Reference to ask-user callback
    var onAskUser: ((String) -> CompletableDeferred<String>)? = null
    var onDebugClick: ((Float, Float) -> Unit)? = null

    private val driver: AccessibilityDriver?
        get() = MindFrameAccessibilityService.instance

    fun loadSystemPrompt(context: Context) {
        if (systemPrompt != null) return
        try {
            val template = context.assets.open("system_prompt_template.txt").bufferedReader().readText()
            val toolsJson = skillRegistry.getAvailableTools()
                .joinToString("\n") { "- ${it.function.name}: ${it.function.description}" }
            val recipes = skillRegistry.getRecipesForPrompt()

            systemPrompt = template
                .replace("{{AVAILABLE_TOOLS}}", toolsJson)
                .replace("{{AVAILABLE_RECIPES}}", recipes)

            Log.i(TAG, "System prompt loaded (${systemPrompt!!.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load system prompt", e)
            systemPrompt = "You are MindFrame, an AI assistant on an Android phone."
        }
    }

    /**
     * Start a new session. Clears all conversation history and re-injects the system prompt.
     * Called once when the OverlayService is created (fresh app launch).
     */
    fun startNewSession() {
        forceStop(showStopMessage = true) // Reuse existing stop logic
        conversationHistory.clear()
        conversationHistory.add(OllamaMessage(role = "system", content = systemPrompt ?: ""))
        _chatMessages.resetReplayCache() // Wipe UI history for the new session
        currentSessionLog = null
        currentSkillType = SkillType.NEW
        sessionActive = true
        _status.value = AgentStatus.Idle
        Log.i(TAG, "═══ NEW SESSION STARTED ═══")
        logHistoryStats("Session initialized")
    }

    /**
     * Execute a user task using the Reasoning-Action-Verification loop.
     * The conversation history is preserved across multiple calls (multi-turn).
     */
    fun executeTask(task: String, scope: CoroutineScope) {
        // Cancel any existing task
        currentJob?.cancel()

        currentJob = scope.launch(Dispatchers.IO) {
            try {
                _status.value = AgentStatus.Thinking(0)
                emitChat(ChatMessage.User(task))

                // If memory awareness is disabled, clear history for the LLM but keep UI bubbles
                if (!settingsRepository.isMemoryAware) {
                    Log.i(TAG, "Memory awareness disabled: clearing LLM context for new task")
                    conversationHistory.clear()
                    conversationHistory.add(OllamaMessage(role = "system", content = systemPrompt ?: ""))
                }
                
                // Ensure session is active (safety net if startNewSession wasn't called)
                if (!sessionActive) {
                    Log.w(TAG, "No active session found, starting one automatically")
                    startNewSession()
                }

                // Initialize session log for this specific task
                currentSessionLog = SessionLog(taskDescription = task)
                currentSkillType = SkillType.NEW // Default to NEW

                // Add user task to the ongoing conversation
                conversationHistory.add(OllamaMessage(role = "user", content = task))
                Log.i(TAG, "── New Task: \"$task\" ──")
                logHistoryStats("Task started")

                // Run the Reasoning-Action-Verification loop
                for (step in 1..MAX_STEPS) {
                    if (!isActive) break

                    Log.i(TAG, "=== Step $step / $MAX_STEPS ===")
                    _status.value = AgentStatus.Thinking(step)
                    logHistoryStats("Step $step")

                    // 1. OBSERVE: Capture current screen
                    val uiSnapshot = captureScreen()

                    // Add observation to conversation
                    if (step > 1) {
                        conversationHistory.add(
                            OllamaMessage(
                                role = "user",
                                content = "Current screen state:\n$uiSnapshot"
                            )
                        )
                    } else {
                        // First step: append screen state to the user's task
                        val lastIdx = conversationHistory.lastIndex
                        val lastMsg = conversationHistory[lastIdx]
                        conversationHistory[lastIdx] = lastMsg.copy(
                            content = "${lastMsg.content}\n\nCurrent screen state:\n$uiSnapshot"
                        )
                    }

                    // 2. THINK: Send to LLM
                    val response = ollamaProvider.sendMessage(
                        messages = conversationHistory,
                        tools = skillRegistry.getAvailableTools()
                    )

                    // Clean the thought if it contains a manual tool call string
                    val cleanedThought = response.thought.replace(Regex("\\[tool_call:.*?\\]"), "").trim()
                    Log.d(TAG, "LLM thought: $cleanedThought")
                    if (cleanedThought.isNotBlank()) {
                        emitChat(ChatMessage.Thought(cleanedThought))
                    }

                    // Add assistant response to conversation
                    conversationHistory.add(
                        OllamaMessage(role = "assistant", content = response.rawContent)
                    )

                    // 3. ACT: Execute tool call if present
                    if (response.toolCall != null) {
                        _status.value = AgentStatus.Acting(response.toolCall.name)
                        Log.i(TAG, "Executing tool: ${response.toolCall.name}(${response.toolCall.arguments})")

                        val currentDriver = driver
                        if (currentDriver == null) {
                            val error = "Accessibility service not connected. Please enable it in Settings."
                            _status.value = AgentStatus.Error(error)
                            emitChat(ChatMessage.Agent("❌ $error"))
                            return@launch
                        }

                        // Hide overlay before action to prevent blocking
                        onHideOverlay?.invoke()
                        delay(200) // Give WindowManager time to hide

                        val toolResult = skillRegistry.executeTool(
                            response.toolCall.name,
                            response.toolCall.arguments,
                            currentDriver
                        )

                        // Show overlay after action
                        onShowOverlay?.invoke()

                        Log.d(TAG, "Tool result: $toolResult")

                        // Handle special tool results
                        when {
                             toolResult.startsWith("TASK_COMPLETE:") -> {
                                 val summary = toolResult.removePrefix("TASK_COMPLETE:")
                                 
                                 // Use the skill type tracked via tool calls
                                 _status.value = AgentStatus.Completed(summary, currentSkillType)
                                 
                                 // Emit final feedback message
                                 val emoji = when(currentSkillType) {
                                     SkillType.LEARNED -> "✨"
                                     SkillType.SYSTEM -> "📚"
                                     SkillType.NEW -> "📝"
                                 }
                                 val feedback = when(currentSkillType) {
                                     SkillType.LEARNED -> "Used a previously learned skill!"
                                     SkillType.SYSTEM -> "Used a built-in pre-skill!"
                                     SkillType.NEW -> "Learned a new skill from this task!"
                                 }
                                 emitChat(ChatMessage.Agent("$emoji $feedback"))
                                 emitChat(ChatMessage.Agent("✅ $summary"))
                                 
                                 currentSessionLog?.wasSuccessful = true

                                 // Log session step
                                 currentSessionLog?.steps?.add(
                                     SessionStep(step, uiSnapshot, response.thought, response.toolCall, toolResult)
                                 )
                                 
                                 // Trigger self-learning if it's a new skill
                                 if (currentSkillType == SkillType.NEW) {
                                     currentSessionLog?.let { scope.launch { skillGenerator.generateFromSession(it) } }
                                 }
                                 
                                 return@launch
                             }
                            toolResult.startsWith("TASK_ERROR:") -> {
                                val reason = toolResult.removePrefix("TASK_ERROR:")
                                _status.value = AgentStatus.Error(reason)
                                emitChat(ChatMessage.Agent("❌ $reason"))
                                currentSessionLog?.wasSuccessful = false
                                return@launch
                            }
                            toolResult.startsWith("WAITING_FOR_USER:") -> {
                                val question = toolResult.removePrefix("WAITING_FOR_USER:")
                                _status.value = AgentStatus.WaitingForUser
                                emitChat(ChatMessage.Agent("❓ $question"))

                                // Wait for user response
                                val userAnswer = onAskUser?.invoke(question)?.await() ?: "No response"
                                emitChat(ChatMessage.User(userAnswer))
                                conversationHistory.add(
                                    OllamaMessage(role = "user", content = "User response: $userAnswer")
                                )
                                continue
                            }
                             toolResult.startsWith("SKILL_STARTED:") -> {
                                 val skillName = toolResult.removePrefix("SKILL_STARTED:").trim()
                                 // Track state based on explicit tool call name
                                 currentSkillType = when {
                                     skillRegistry.getLearnedRecipeNames().contains(skillName) -> SkillType.LEARNED
                                     skillRegistry.getSystemRecipeNames().contains(skillName) -> SkillType.SYSTEM
                                     else -> SkillType.NEW
                                 }
                                 Log.i(TAG, "Explicit Skill Started: $skillName (Type: $currentSkillType)")
                             }
                        }

                        // Add tool result to conversation
                        conversationHistory.add(
                            OllamaMessage(role = "user", content = "Tool result: $toolResult")
                        )

                        // 4. VERIFY: Wait for screen to change
                        _status.value = AgentStatus.Verifying(step)
                        waitForScreenChange()

                        // Log session step
                        currentSessionLog?.steps?.add(
                            SessionStep(step, uiSnapshot, response.thought, response.toolCall, toolResult)
                        )

                    } else {
                        // No tool call — LLM is just talking or explaining.
                        // We mark this as Completed so the UI clears the "Waiting..." status.
                        _status.value = AgentStatus.Completed(cleanedThought, currentSkillType)
                        
                        currentSessionLog?.steps?.add(
                            SessionStep(step, uiSnapshot, response.thought, null, "Conversational response")
                        )
                        return@launch
                    }
                }

                // If we reach here, we hit the step limit
                _status.value = AgentStatus.Error("Reached maximum step limit ($MAX_STEPS). Task may be incomplete.")
                emitChat(ChatMessage.Agent("⚠️ Reached step limit. The task may be incomplete."))

            } catch (e: CancellationException) {
                _status.value = AgentStatus.Error("Task was cancelled")
                Log.i(TAG, "Task cancelled")
            } catch (e: Exception) {
                _status.value = AgentStatus.Error("Unexpected error: ${e.message}")
                Log.e(TAG, "Task execution failed", e)
                emitChat(ChatMessage.Agent("❌ Error: ${e.message}"))
            }
        }
    }

    /**
     * Force stop the current task.
     */
    fun forceStop(showStopMessage: Boolean) {
        currentJob?.cancel()
        currentJob = null
        _status.value = AgentStatus.Idle
        if (!showStopMessage) {
            emitChat(ChatMessage.Agent("🛑 Task force stopped"))
        }
        Log.i(TAG, "Task force stopped")
    }

    fun getSessionLog(): SessionLog? = currentSessionLog

    fun clearConversation() {
        conversationHistory.clear()
        _chatMessages.resetReplayCache() // Wipe UI history
        currentSessionLog = null
        sessionActive = false
        _status.value = AgentStatus.Idle
        Log.i(TAG, "═══ SESSION ENDED — Conversation cleared ═══")
    }

    /**
     * Logs current conversation history statistics for debugging and future memory management.
     */
    private fun logHistoryStats(context: String) {
        val messageCount = conversationHistory.size
        val totalChars = conversationHistory.sumOf { it.content.length }
        val roleCounts = conversationHistory.groupingBy { it.role }.eachCount()
        Log.d(TAG, "[$context] History: $messageCount msgs, ${totalChars} chars | Breakdown: $roleCounts")
    }

    // --- Private Helpers ---

    private suspend fun captureScreen(): String {
        // Hide overlay before capturing
        onHideOverlay?.invoke()
        delay(OVERLAY_HIDE_DELAY_MS)

        val screenText = driver?.readScreenAsText()
            ?: "[Accessibility service not connected - cannot read screen]"

        // Show overlay again
        onShowOverlay?.invoke()

        return screenText
    }

    private suspend fun waitForScreenChange() {
        val startTime = System.currentTimeMillis()
        val timestampBefore = MindFrameAccessibilityService.instance?.lastWindowChangeTimestamp ?: 0L

        while (System.currentTimeMillis() - startTime < SCREEN_CHANGE_TIMEOUT_MS) {
            val currentTimestamp = MindFrameAccessibilityService.instance?.lastWindowChangeTimestamp ?: 0L
            if (currentTimestamp > timestampBefore) {
                Log.d(TAG, "Screen change detected after ${System.currentTimeMillis() - startTime}ms")
                delay(200) // Small delay for UI to settle
                return
            }
            delay(100)
        }
        Log.d(TAG, "Screen change timeout after ${SCREEN_CHANGE_TIMEOUT_MS}ms — proceeding anyway")
    }

    private fun emitChat(message: ChatMessage) {
        _chatMessages.tryEmit(message)
    }

    sealed class ChatMessage {
        data class User(val text: String) : ChatMessage()
        data class Thought(val text: String) : ChatMessage()
        data class Agent(val text: String) : ChatMessage()
    }
}
