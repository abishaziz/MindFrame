package com.atwenty.personalagent.domain.model

/**
 * Contract for any LLM backend provider.
 * Allows swapping Ollama Cloud for a local model or different API without changing the Orchestrator.
 */
interface ModelProvider {
    /**
     * Sends a list of conversation messages to the LLM along with available tools.
     * Returns an AgentResponse containing the LLM's thought and optional tool call.
     */
    suspend fun sendMessage(
        messages: List<OllamaMessage>,
        tools: List<OllamaTool>? = null
    ): AgentResponse
}
