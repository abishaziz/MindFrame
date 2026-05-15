package com.atwenty.mindframe.domain.entities

enum class SkillType { NEW, SYSTEM, LEARNED }

sealed class AgentStatus {
    data object Idle : AgentStatus()
    data class Thinking(val step: Int) : AgentStatus()
    data class Acting(val action: String) : AgentStatus()
    data class Verifying(val step: Int) : AgentStatus()
    data class Completed(val summary: String, val skillType: SkillType = SkillType.NEW) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
    data object WaitingForUser : AgentStatus()
}
