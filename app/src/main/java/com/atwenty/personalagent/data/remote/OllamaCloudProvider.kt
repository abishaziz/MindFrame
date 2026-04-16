package com.atwenty.personalagent.data.remote

import android.util.Log
import com.atwenty.personalagent.data.local.SettingsRepository
import com.atwenty.personalagent.domain.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OllamaCloudProvider(private val settingsRepository: SettingsRepository) : ModelProvider {

    companion object {
        private const val TAG = "PA_OllamaCloud"
        private const val ENDPOINT = "/api/chat"
        private const val MAX_RETRIES = 3
        private val BACKOFF_DELAYS = listOf(1000L, 2000L, 4000L) // Exponential backoff
    }

    private val gson: Gson = GsonBuilder()
        .setLenient()
        .create()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // LLM responses can take time
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun sendMessage(
        messages: List<OllamaMessage>,
        tools: List<OllamaTool>?
    ): AgentResponse {
        val baseUrl = settingsRepository.ollamaBaseUrl.trimEnd('/')
        val apiKey = settingsRepository.ollamaApiKey
        val model = settingsRepository.modelName

        val ollamaRequest = OllamaRequest(
            model = model,
            messages = messages,
            tools = if (tools?.isNotEmpty() == true) tools else null,
            stream = false
        )

        val requestBody = gson.toJson(ollamaRequest)
        Log.d(TAG, "Sending request to $baseUrl$ENDPOINT (model: $model)")

        var lastException: Exception? = null

        // Tier 1: Retry with exponential backoff
        for (attempt in 0 until MAX_RETRIES) {
            try {
                val httpRequest = Request.Builder()
                    .url("$baseUrl$ENDPOINT")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .build()

                Log.d(TAG, ">>> SENDING REQUEST to LLM...")
                Log.d(TAG, "Request payload: $requestBody")

                val response = client.newCall(httpRequest).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d(TAG, "### RECEIVED RESPONSE from LLM (Status: ${response.code})")
                Log.d(TAG, "Response result: $responseBody")

                if (!response.isSuccessful) {
                    if (response.code >= 500) {
                        // Server error—retry
                        Log.w(TAG, "Server error ${response.code}, attempt ${attempt + 1}")
                        lastException = IOException("Server error: ${response.code}")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(BACKOFF_DELAYS[attempt])
                        }
                        continue
                    }
                    // Client error—don't retry
                    return AgentResponse(
                        thought = "API Error: ${response.code} - $responseBody",
                        toolCall = null,
                        rawContent = responseBody
                    )
                }

                return parseResponse(responseBody)

            } catch (e: IOException) {
                Log.w(TAG, "Network error, attempt ${attempt + 1}: ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(BACKOFF_DELAYS[attempt])
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                lastException = e
                break
            }
        }

        // Tier 3: All retries failed—notify user
        return AgentResponse(
            thought = "Could not reach the AI server after $MAX_RETRIES attempts: ${lastException?.message}",
            toolCall = null,
            rawContent = lastException?.message ?: "Unknown error"
        )
    }

    private fun parseResponse(responseBody: String): AgentResponse {
        return try {
            val ollamaResponse = gson.fromJson(responseBody, OllamaResponse::class.java)

            if (ollamaResponse.error != null) {
                return AgentResponse(
                    thought = "Ollama error: ${ollamaResponse.error}",
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
            // Tier 2: Malformed JSON—tell the LLM to try again
            Log.e(TAG, "Failed to parse response: ${e.message}")
            AgentResponse(
                thought = "Failed to parse LLM response: ${e.message}",
                toolCall = null,
                rawContent = responseBody
            )
        }
    }

    /**
     * Extracts the "thought" field from the LLM's content.
     */
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

    /**
     * Fallback parser for manual tool calls like:
     * [tool_call: name with arg1="val1" arg2="val2"]
     */
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
            Log.e(TAG, "Failed to parse manual tool call from text", e)
            return null
        }
    }
}
