package com.atwenty.mindframe.domain.entities

// --- Session Logging for Self-Learning ---

data class SessionLog(
    val taskDescription: String,
    val steps: MutableList<SessionStep> = mutableListOf(),
    var wasSuccessful: Boolean? = null
)

data class SessionStep(
    val stepNumber: Int,
    val uiSnapshot: String,
    val llmThought: String,
    val toolCall: ToolCall?,
    val result: String
)
