package com.atwenty.mindframe.llm.ollamacloud

import com.google.gson.annotations.SerializedName

// --- Ollama Cloud API Request/Response DTOs ---

data class Request(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val stream: Boolean = false
)

data class Message(
    val role: String,
    val content: String = "",
    val images: List<String>? = null, // Base64 encoded images for vision
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
)

data class Tool(
    val type: String = "function",
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

data class ToolProperty(
    val type: String,
    val description: String,
    @SerializedName("enum") val enumValues: List<String>? = null
)

data class ToolCall(
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String,
    val arguments: Map<String, com.google.gson.JsonElement> = emptyMap()
)

data class Response(
    val model: String? = null,
    val message: Message? = null,
    val done: Boolean = false,
    @SerializedName("done_reason") val doneReason: String? = null,
    val error: String? = null
)
