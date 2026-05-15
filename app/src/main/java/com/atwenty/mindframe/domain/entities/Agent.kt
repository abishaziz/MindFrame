package com.atwenty.mindframe.domain.entities

// --- Generic Agent Models (API Agnostic) ---

enum class ProviderType { OLLAMA_CLOUD, OPENROUTER }

data class AgentMessage(
    val role: String,
    val content: String = "",
    val imagesBase64: List<String>? = null,
    val toolCalls: List<ToolCall>? = null
)

data class AgentTool(
    val type: String = "function",
    val function: AgentFunction
)

data class AgentFunction(
    val name: String,
    val description: String,
    val parameters: AgentParameters
)

data class AgentParameters(
    val type: String = "object",
    val properties: Map<String, AgentProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

data class AgentProperty(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null
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
