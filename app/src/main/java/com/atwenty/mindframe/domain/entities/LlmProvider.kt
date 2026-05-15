package com.atwenty.mindframe.domain.entities

/**
 * Contract for any LLM backend provider.
 */
interface LlmProvider {
    /**
     * Sends a list of conversation messages to the LLM along with available tools.
     * Returns an AgentResponse containing the LLM's thought and optional tool call.
     */
    suspend fun sendMessage(
        messages: List<AgentMessage>,
        tools: List<AgentTool>? = null
    ): AgentResponse
}
