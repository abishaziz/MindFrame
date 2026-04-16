package com.atwenty.personalagent.domain.usecase

import android.content.Context
import android.util.Log
import com.atwenty.personalagent.data.remote.OllamaCloudProvider
import com.atwenty.personalagent.domain.model.*
import com.atwenty.personalagent.service.accessibility.AccessibilityDriver
import com.atwenty.personalagent.service.accessibility.PersonalAgentAccessibilityService
import com.atwenty.personalagent.skills.registry.SkillRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentOrchestrator(
    private val ollamaProvider: OllamaCloudProvider,
    private val skillRegistry: SkillRegistry
) {
    companion object {
        private const val TAG = "PA_Orchestrator"
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

    // System prompt, loaded once and cached
    private var systemPrompt: String? = null

    // Reference to overlay hide/show callbacks
    var onHideOverlay: (() -> Unit)? = null
    var onShowOverlay: (() -> Unit)? = null
    // Reference to ask-user callback
    var onAskUser: ((String) -> CompletableDeferred<String>)? = null

    private val driver: AccessibilityDriver?
        get() = PersonalAgentAccessibilityService.instance

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
            systemPrompt = "You are PersonalAgent, an AI assistant on an Android phone."
        }
    }

    /**
     * Execute a user task using the Reasoning-Action-Verification loop.
     */
    fun executeTask(task: String, scope: CoroutineScope) {
        // Cancel any existing task
        currentJob?.cancel()

        currentJob = scope.launch(Dispatchers.IO) {
            try {
                _status.value = AgentStatus.Thinking(0)
                emitChat(ChatMessage.User(task))

                // Initialize conversation
                conversationHistory.clear()
                conversationHistory.add(OllamaMessage(role = "system", content = systemPrompt ?: ""))

                // Initialize session log
                currentSessionLog = SessionLog(taskDescription = task)

                // Add user task
                conversationHistory.add(OllamaMessage(role = "user", content = task))

                // Run the Reasoning-Action-Verification loop
                for (step in 1..MAX_STEPS) {
                    if (!isActive) break

                    Log.i(TAG, "=== Step $step / $MAX_STEPS ===")
                    _status.value = AgentStatus.Thinking(step)

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

                    Log.d(TAG, "LLM thought: ${response.thought}")
                    emitChat(ChatMessage.Agent(response.thought))

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

                        val toolResult = skillRegistry.executeTool(
                            response.toolCall.name,
                            response.toolCall.arguments,
                            currentDriver
                        )

                        Log.d(TAG, "Tool result: $toolResult")

                        // Handle special tool results
                        when {
                            toolResult.startsWith("TASK_COMPLETE:") -> {
                                val summary = toolResult.removePrefix("TASK_COMPLETE:")
                                _status.value = AgentStatus.Completed(summary)
                                emitChat(ChatMessage.Agent("✅ $summary"))
                                currentSessionLog?.wasSuccessful = true

                                // Log session step
                                currentSessionLog?.steps?.add(
                                    SessionStep(step, uiSnapshot, response.thought, response.toolCall, toolResult)
                                )
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
                        // We must stop the loop here, otherwise it will re-observe the same screen and repeat itself.
                        _status.value = AgentStatus.WaitingForUser
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
    fun forceStop() {
        currentJob?.cancel()
        currentJob = null
        _status.value = AgentStatus.Idle
        emitChat(ChatMessage.Agent("🛑 Task force stopped"))
        Log.i(TAG, "Task force stopped")
    }

    fun getSessionLog(): SessionLog? = currentSessionLog

    fun clearConversation() {
        conversationHistory.clear()
        currentSessionLog = null
        _status.value = AgentStatus.Idle
    }

    // --- Private Helpers ---

    private fun captureScreen(): String {
        // Hide overlay before capturing
        onHideOverlay?.invoke()
        Thread.sleep(OVERLAY_HIDE_DELAY_MS)

        val screenText = driver?.readScreenAsText()
            ?: "[Accessibility service not connected - cannot read screen]"

        // Show overlay again
        onShowOverlay?.invoke()

        return screenText
    }

    private suspend fun waitForScreenChange() {
        val startTime = System.currentTimeMillis()
        val timestampBefore = PersonalAgentAccessibilityService.instance?.lastWindowChangeTimestamp ?: 0L

        while (System.currentTimeMillis() - startTime < SCREEN_CHANGE_TIMEOUT_MS) {
            val currentTimestamp = PersonalAgentAccessibilityService.instance?.lastWindowChangeTimestamp ?: 0L
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
        data class Agent(val text: String) : ChatMessage()
    }
}
