package com.atwenty.mindframe.llm

import android.util.Log
import com.atwenty.mindframe.domain.entities.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Base class for all LLM API providers.
 * Contains shared networking infrastructure (HTTP client, retry logic)
 * and shared response parsing utilities (thought extraction, fallback tool-call parsing).
 *
 * Subclasses only need to:
 * 1. Build their provider-specific HTTP request (URL, headers, JSON payload).
 * 2. Implement their provider-specific response parsing (converting their JSON to AgentResponse).
 */
abstract class BaseLlmProvider : LlmProvider {

    companion object {
        private const val TAG = "MF_BaseLlm"
        private const val MAX_RETRIES = 3
        private val BACKOFF_DELAYS = listOf(1000L, 2000L, 4000L)
    }

    protected val gson: Gson = GsonBuilder().setLenient().create()

    protected val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // --- Shared Networking ---

    /**
     * Executes an HTTP request with exponential backoff retry logic.
     * Handles server errors (5xx), rate limits (429), and network failures.
     *
     * @param tag The log tag for the calling provider (e.g., "MF_OllamaCloud").
     * @param request The fully-built OkHttp Request.
     * @param parseBody A provider-specific lambda that converts the raw response body
     *                  string into an AgentResponse.
     * @return An AgentResponse from the LLM, or an error response if all retries fail.
     */
    protected suspend fun executeWithRetry(
        tag: String,
        request: Request,
        parseBody: (String) -> AgentResponse
    ): AgentResponse {
        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            try {
                Log.d(tag, ">>> SENDING REQUEST to LLM (attempt ${attempt + 1})...")

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d(tag, "### RECEIVED RESPONSE (Status: ${response.code})")

                if (!response.isSuccessful) {
                    if (response.code >= 500 || response.code == 429) {
                        // Server error or rate limit — retry with backoff
                        Log.w(tag, "Server/Rate limit error ${response.code}, attempt ${attempt + 1}")
                        lastException = IOException("Server error: ${response.code}")
                        if (attempt < MAX_RETRIES - 1) {
                            delay(BACKOFF_DELAYS[attempt])
                        }
                        continue
                    }
                    // Client error (4xx except 429) — don't retry
                    return AgentResponse(
                        thought = "API Error: ${response.code} - $responseBody",
                        toolCall = null,
                        rawContent = responseBody
                    )
                }

                return parseBody(responseBody)

            } catch (e: IOException) {
                Log.w(tag, "Network error, attempt ${attempt + 1}: ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(BACKOFF_DELAYS[attempt])
                }
            } catch (e: Exception) {
                Log.e(tag, "Unexpected error", e)
                lastException = e
                break
            }
        }

        return AgentResponse(
            thought = "Could not reach the AI server after $MAX_RETRIES attempts: ${lastException?.message}",
            toolCall = null,
            rawContent = lastException?.message ?: "Unknown error"
        )
    }

    // --- Shared Response Parsing ---

    /**
     * Extracts the "thought" field from the LLM's content.
     * If the content is a JSON object with a "thought" key, returns that value.
     * Otherwise, returns the raw content as-is.
     */
    protected fun extractThought(content: String): String {
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
     * Fallback parser for manual tool calls embedded in text like:
     * [tool_call: name with arg1="val1" arg2="val2"]
     *
     * Used when the LLM doesn't use native JSON tool-calling and instead
     * writes the tool call as a formatted string in its response.
     */
    protected fun extractToolCallFromText(content: String): ToolCall? {
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
