package com.atwenty.mindframe.llm.ollamacloud

import android.util.Log
import com.atwenty.mindframe.data.local.SettingsRepository
import com.atwenty.mindframe.llm.BaseLlmProvider
import com.atwenty.mindframe.domain.entities.*

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OllamaCloudProvider(private val settingsRepository: SettingsRepository) : BaseLlmProvider() {

    companion object {
        private const val TAG = "MF_OllamaCloud"
        private const val ENDPOINT = "/api/chat"
    }

    override suspend fun sendMessage(
        messages: List<AgentMessage>,
        tools: List<AgentTool>?
    ): AgentResponse {
        val baseUrl = settingsRepository.ollamaCloudBaseUrl.trimEnd('/')
        val apiKey = settingsRepository.ollamaCloudApiKey
        val model = settingsRepository.ollamaCloudModel

        val ollamaMessages = messages.map {
            Message(
                role = it.role,
                content = it.content,
                images = it.imagesBase64,
                toolCalls = it.toolCalls?.map { tc ->
                    ToolCall(
                        function = ToolCallFunction(
                            name = tc.name,
                            arguments = tc.arguments.mapValues { arg -> com.google.gson.JsonPrimitive(arg.value) }
                        )
                    )
                }
            )
        }

        val ollamaTools = tools?.map {
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

        val ollamaRequest = com.atwenty.mindframe.llm.ollamacloud.Request(
            model = model,
            messages = ollamaMessages,
            tools = if (ollamaTools?.isNotEmpty() == true) ollamaTools else null,
            stream = false
        )

        val requestBody = gson.toJson(ollamaRequest)
        Log.d(TAG, "Sending request to $baseUrl$ENDPOINT (model: $model)")

        val httpRequest = Request.Builder()
            .url("$baseUrl$ENDPOINT")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .apply {
                if (apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        return executeWithRetry(TAG, httpRequest, ::parseResponse)
    }

    private fun parseResponse(responseBody: String): AgentResponse {
        return try {
            val ollamaResponse = gson.fromJson(responseBody, Response::class.java)

            if (ollamaResponse.error != null) {
                return AgentResponse(
                    thought = "Ollama Cloud error: ${ollamaResponse.error}",
                    toolCall = null,
                    rawContent = responseBody
                )
            }

            val message = ollamaResponse.message ?: return AgentResponse(
                thought = "Empty response from LLM",
                toolCall = null,
                rawContent = responseBody
            )

            // Check for tool calls in the response
            val toolCall = message.toolCalls?.firstOrNull()?.let { tc ->
                ToolCall(
                    name = tc.function.name,
                    arguments = tc.function.arguments.mapValues { 
                        if (it.value.isJsonPrimitive) it.value.asString else it.value.toString()
                    }
                )
            }

            // Try to extract structured thought from content
            val content = message.content
            val thought = extractThought(content)

            // Fallback for manual tool calls in text
            val finalToolCall = toolCall ?: extractToolCallFromText(content)

            AgentResponse(
                thought = thought,
                toolCall = finalToolCall,
                rawContent = content
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            AgentResponse(
                thought = "Failed to parse LLM response: ${e.message}",
                toolCall = null,
                rawContent = responseBody
            )
        }
    }
}
