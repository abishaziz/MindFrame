package com.atwenty.mindframe.data.remote

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
