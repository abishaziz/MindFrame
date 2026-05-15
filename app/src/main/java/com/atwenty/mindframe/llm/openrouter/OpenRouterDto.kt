package com.atwenty.mindframe.llm.openrouter

import com.google.gson.annotations.SerializedName

// --- OpenRouter API Request/Response DTOs ---

data class Request(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val stream: Boolean = false
)

data class Message(
    val role: String,
    val content: Any, // Can be String or List<ContentPart>
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null, // For sending tool results back
    val name: String? = null // Often required when role is "tool"
)

data class ContentPart(
    val type: String, // "text" or "image_url"
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    val url: String
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
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String,
    val arguments: String // OpenAI returns arguments as a stringified JSON object
)

data class Response(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val error: ApiError? = null
)

data class Choice(
    val index: Int,
    val message: ResponseMessage,
    @SerializedName("finish_reason") val finishReason: String?
)

data class ResponseMessage(
    val role: String,
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
)

data class ApiError(
    val message: String,
    val type: String?,
    val code: String?
)
