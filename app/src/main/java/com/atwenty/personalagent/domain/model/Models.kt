package com.atwenty.personalagent.domain.model

import com.google.gson.annotations.SerializedName

// --- Ollama API Request/Response Models ---

data class OllamaRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val tools: List<OllamaTool>? = null,
    val stream: Boolean = false
)

data class OllamaMessage(
    val role: String,
    val content: String = "",
    val images: List<String>? = null, // Base64 encoded images for vision
    @SerializedName("tool_calls") val toolCalls: List<OllamaToolCall>? = null
)

data class OllamaTool(
    val type: String = "function",
    val function: OllamaFunction
)

data class OllamaFunction(
    val name: String,
    val description: String,
    val parameters: OllamaParameters
)

data class OllamaParameters(
    val type: String = "object",
    val properties: Map<String, OllamaProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

data class OllamaProperty(
    val type: String,
    val description: String,
    @SerializedName("enum") val enumValues: List<String>? = null
)

data class OllamaToolCall(
    val function: OllamaToolCallFunction
)

data class OllamaToolCallFunction(
    val name: String,
    val arguments: Map<String, com.google.gson.JsonElement> = emptyMap()
)

data class OllamaResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerializedName("done_reason") val doneReason: String? = null,
    val error: String? = null
)

// --- Agent Internal Models ---

data class AgentResponse(
    val thought: String,
    val toolCall: ToolCall?,
    val rawContent: String
)

data class ToolCall(
    val name: String,
    val arguments: Map<String, String>
)

enum class SkillType { NEW, SYSTEM, LEARNED }

sealed class AgentStatus {
    data object Idle : AgentStatus()
    data class Thinking(val step: Int) : AgentStatus()
    data class Acting(val action: String) : AgentStatus()
    data class Verifying(val step: Int) : AgentStatus()
    data class Completed(val summary: String, val skillType: SkillType = SkillType.NEW) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
    data object WaitingForUser : AgentStatus()
}

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

// --- UI Node for Compacted Tree ---

data class UiNode(
    val id: String,      // e.g., "node_5"
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val bounds: String,  // e.g., "[0,0][1080,200]"
    val children: List<UiNode> = emptyList()
) {
    fun toCompactString(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val attrs = mutableListOf<String>()

        if (isClickable) attrs.add("clickable")
        if (isEditable) attrs.add("editable")
        if (isScrollable) attrs.add("scrollable")
        if (isCheckable) attrs.add("checkable${if (isChecked) ":checked" else ":unchecked"}")

        sb.append("$prefix[$id] $className")
        if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
        if (!contentDescription.isNullOrBlank()) sb.append(" desc=\"$contentDescription\"")
        if (attrs.isNotEmpty()) sb.append(" {${attrs.joinToString(",")}}")
        sb.append(" $bounds")
        sb.appendLine()

        children.forEach { sb.append(it.toCompactString(indent + 1)) }
        return sb.toString()
    }
}
