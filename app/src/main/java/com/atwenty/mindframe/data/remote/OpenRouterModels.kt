package com.atwenty.mindframe.data.remote

import com.google.gson.annotations.SerializedName

// --- OpenRouter/OpenAI API Request/Response Models ---

data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val tools: List<OpenRouterTool>? = null,
    val stream: Boolean = false
)

data class OpenRouterMessage(
    val role: String,
    val content: Any, // Can be String or List<OpenRouterContentPart>
    @SerializedName("tool_calls") val toolCalls: List<OpenRouterToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null, // For sending tool results back
    val name: String? = null // Often required when role is "tool"
)

data class OpenRouterContentPart(
    val type: String, // "text" or "image_url"
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: OpenRouterImageUrl? = null
)

data class OpenRouterImageUrl(
    val url: String
)

data class OpenRouterTool(
    val type: String = "function",
    val function: OpenRouterFunction
)

data class OpenRouterFunction(
    val name: String,
    val description: String,
    val parameters: OpenRouterParameters
)

data class OpenRouterParameters(
    val type: String = "object",
    val properties: Map<String, OpenRouterProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

data class OpenRouterProperty(
    val type: String,
    val description: String,
    @SerializedName("enum") val enumValues: List<String>? = null
)

data class OpenRouterToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenRouterToolCallFunction
)

data class OpenRouterToolCallFunction(
    val name: String,
    val arguments: String // OpenAI returns arguments as a stringified JSON object
)

data class OpenRouterResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenRouterChoice>? = null,
    val error: OpenRouterError? = null
)

data class OpenRouterChoice(
    val index: Int,
    val message: OpenRouterResponseMessage,
    @SerializedName("finish_reason") val finishReason: String?
)

data class OpenRouterResponseMessage(
    val role: String,
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<OpenRouterToolCall>? = null
)

data class OpenRouterError(
    val message: String,
    val type: String?,
    val code: String?
)
