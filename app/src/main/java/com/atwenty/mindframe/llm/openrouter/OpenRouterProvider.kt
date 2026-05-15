package com.atwenty.mindframe.llm.openrouter

import android.util.Log
import com.atwenty.mindframe.data.local.SettingsRepository
import com.atwenty.mindframe.llm.BaseLlmProvider
import com.atwenty.mindframe.domain.entities.*
import com.google.gson.reflect.TypeToken

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenRouterProvider(private val settingsRepository: SettingsRepository) : BaseLlmProvider() {

    companion object {
        private const val TAG = "MF_OpenRouter"
    }

    override suspend fun sendMessage(
        messages: List<AgentMessage>,
        tools: List<AgentTool>?
    ): AgentResponse {
        val baseUrl = settingsRepository.openRouterBaseUrl.trimEnd('/')
        val apiKey = settingsRepository.openRouterApiKey
        val model = settingsRepository.openRouterModel

        if (apiKey.isBlank()) {
            return AgentResponse(
                thought = "OpenRouter API Key is missing. Please configure it in Settings.",
                toolCall = null,
                rawContent = "API Key Error"
            )
        }

        // 1. Map Tools
        val openRouterTools = tools?.map {
            Tool(
                type = it.type,
                function = ToolFunction(
                    name = it.function.name,
                    description = it.function.description,
                    parameters = ToolParameters(
                        type = it.function.parameters.type,
                        properties = it.function.parameters.properties.mapValues { prop ->
                            ToolProperty(
                                type = prop.value.type,
                                description = prop.value.description,
                                enumValues = prop.value.enumValues
                            )
                        },
                        required = it.function.parameters.required
                    )
                )
            )
        }

        // 2. Map Messages (Handling Multimodal Vision properly)
        val openRouterMessages = messages.map { msg ->
            val content: Any = if (!msg.imagesBase64.isNullOrEmpty()) {
                val parts = mutableListOf<ContentPart>()
                if (msg.content.isNotBlank()) {
                    parts.add(ContentPart(type = "text", text = msg.content))
                }
                msg.imagesBase64.forEach { base64 ->
                    // OpenRouter expects the data URI format for base64 images
                    parts.add(
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64")
                        )
                    )
                }
                parts
            } else {
                msg.content
            }

            Message(
                role = msg.role,
                content = content
            )
        }

        val openRouterRequest = com.atwenty.mindframe.llm.openrouter.Request(
            model = model,
            messages = openRouterMessages,
            tools = if (openRouterTools?.isNotEmpty() == true) openRouterTools else null,
            stream = false
        )

        val requestBody = gson.toJson(openRouterRequest)
        Log.d(TAG, "Sending request to $baseUrl/chat/completions (model: $model)")

        val httpRequest = Request.Builder()
            // If baseUrl already ends with /chat/completions, don't append it again
            .url(if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/abishaziz/PersonalAgent") // OpenRouter requests this
            .addHeader("X-Title", "MindFrame Android Agent")
            .build()

        return executeWithRetry(TAG, httpRequest, ::parseResponse)
    }

    private fun parseResponse(responseBody: String): AgentResponse {
        return try {
            val response = gson.fromJson(responseBody, Response::class.java)

            if (response.error != null) {
                return AgentResponse(
                    thought = "OpenRouter Error: ${response.error.message}",
                    toolCall = null,
                    rawContent = responseBody
                )
            }

            val choice = response.choices?.firstOrNull() ?: return AgentResponse(
                thought = "Empty response from OpenRouter",
                toolCall = null,
                rawContent = responseBody
            )

            val message = choice.message
            val content = message.content ?: ""

            // OpenRouter tool calls have arguments as a raw JSON string
            val toolCall = message.toolCalls?.firstOrNull()?.let { tc ->
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val argsMap: Map<String, Any> = try {
                    gson.fromJson(tc.function.arguments, type) ?: emptyMap()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse tool arguments: ${tc.function.arguments}", e)
                    emptyMap()
                }

                // Convert all values to string for our internal ToolCall model
                val stringArgs = argsMap.mapValues {
                    if (it.value is Double && (it.value as Double) % 1 == 0.0) {
                        (it.value as Double).toLong().toString()
                    } else {
                        it.value.toString()
                    }
                }

                ToolCall(
                    name = tc.function.name,
                    arguments = stringArgs
                )
            }

            // Extract thought from content block if possible, else use raw content
            val thought = extractThought(content)

            // Fallback
            val finalToolCall = toolCall ?: extractToolCallFromText(content)

            AgentResponse(
                thought = thought,
                toolCall = finalToolCall,
                rawContent = content
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse OpenRouter response: ${e.message}")
            AgentResponse(
                thought = "Failed to parse LLM response: ${e.message}",
                toolCall = null,
                rawContent = responseBody
            )
        }
    }
}
