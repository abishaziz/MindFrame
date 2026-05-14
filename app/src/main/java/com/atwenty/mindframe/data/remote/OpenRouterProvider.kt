package com.atwenty.mindframe.data.remote

import android.util.Log
import com.atwenty.mindframe.data.local.SettingsRepository
import com.atwenty.mindframe.domain.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenRouterProvider(private val settingsRepository: SettingsRepository) : ModelProvider {

    companion object {
        private const val TAG = "MF_OpenRouter"
        private const val MAX_RETRIES = 3
        private val BACKOFF_DELAYS = listOf(1000L, 2000L, 4000L)
    }

    private val gson: Gson = GsonBuilder().setLenient().create()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
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
            OpenRouterTool(
                type = it.type,
                function = OpenRouterFunction(
                    name = it.function.name,
                    description = it.function.description,
                    parameters = OpenRouterParameters(
                        type = it.function.parameters.type,
                        properties = it.function.parameters.properties.mapValues { prop ->
                            OpenRouterProperty(
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
                val parts = mutableListOf<OpenRouterContentPart>()
                if (msg.content.isNotBlank()) {
                    parts.add(OpenRouterContentPart(type = "text", text = msg.content))
                }
                msg.imagesBase64.forEach { base64 ->
                    // OpenRouter expects the data URI format for base64 images
                    parts.add(
                        OpenRouterContentPart(
                            type = "image_url",
                            imageUrl = OpenRouterImageUrl(url = "data:image/jpeg;base64,$base64")
                        )
                    )
                }
                parts
            } else {
                msg.content
            }

            OpenRouterMessage(
                role = msg.role,
                content = content
            )
        }

        val request = OpenRouterRequest(
            model = model,
            messages = openRouterMessages,
            tools = if (openRouterTools?.isNotEmpty() == true) openRouterTools else null,
            stream = false
        )

        val requestBody = gson.toJson(request)
        Log.d(TAG, "Sending request to $baseUrl/chat/completions (model: $model)")

        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                val httpRequest = Request.Builder()
                    // If baseUrl already ends with /chat/completions, don't append it again
                    .url(if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "https://github.com/abishaziz/PersonalAgent") // OpenRouter requests this
                    .addHeader("X-Title", "MindFrame Android Agent")
                    .build()

                Log.d(TAG, ">>> SENDING REQUEST to OpenRouter...")
                
                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d(TAG, "### RECEIVED RESPONSE from OpenRouter (Status: ${response.code})")

                if (!response.isSuccessful) {
                    if (response.code >= 500 || response.code == 429) {
                        Log.w(TAG, "Server/Rate limit error ${response.code}, attempt ${attempt + 1}")
                        lastException = IOException("Server error: ${response.code}")
                        if (attempt < MAX_RETRIES - 1) delay(BACKOFF_DELAYS[attempt])
                        continue
                    }
                    return AgentResponse(
                        thought = "OpenRouter API Error: ${response.code} - $responseBody",
                        toolCall = null,
                        rawContent = responseBody
                    )
                }

                return parseResponse(responseBody)

            } catch (e: IOException) {
                Log.w(TAG, "Network error, attempt ${attempt + 1}: ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES - 1) delay(BACKOFF_DELAYS[attempt])
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                lastException = e
                break
            }
        }

        return AgentResponse(
            thought = "Could not reach OpenRouter after $MAX_RETRIES attempts: ${lastException?.message}",
            toolCall = null,
            rawContent = lastException?.message ?: "Unknown error"
        )
    }

    private fun parseResponse(responseBody: String): AgentResponse {
        return try {
            val response = gson.fromJson(responseBody, OpenRouterResponse::class.java)

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

    private fun extractThought(content: String): String {
        return try {
            val jsonContent = content.trim()
            if (jsonContent.startsWith("{")) {
                val obj = gson.fromJson(jsonContent, com.google.gson.JsonObject::class.java)
                if (obj.has("thought")) {
                    obj.get("thought").asString
                } else {
                    content
                }
            } else {
                content
            }
        } catch (e: Exception) {
            content
        }
    }

    private fun extractToolCallFromText(content: String): ToolCall? {
        try {
            val toolRegex = Regex("\\[tool_call:\\s*(\\w+)(?:\\s+with\\s+(.+))?\\s*\\]")
            val match = toolRegex.find(content) ?: return null

            val name = match.groupValues[1]
            val argsString = match.groupValues.getOrNull(2) ?: ""

            val argsMap = mutableMapOf<String, String>()
            val argRegex = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")
            argRegex.findAll(argsString).forEach { argMatch ->
                val argName = argMatch.groupValues[1]
                val argValue = argMatch.groupValues[2]
                argsMap[argName] = argValue
            }

            return ToolCall(name, argsMap)
        } catch (e: Exception) {
            return null
        }
    }
}
