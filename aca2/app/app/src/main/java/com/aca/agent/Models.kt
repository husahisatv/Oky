package com.aca.agent

data class Skill(
    val name: String, val app: String, val target: String, val control: String, val steps: Int,
    val runnable: Boolean, val needsConfirmation: Boolean, val evidence: String, val note: String
)

data class TaskView(
    val id: String, val kind: String, val skill: String?, val intent: String?, val state: String,
    val evidence: String, val reason: String, val exitCode: Int?, val started: Double, val ended: Double?,
    val output: List<String>
)

data class Health(
    val reachable: Boolean, val engine: Boolean, val rish: Boolean, val controlSh: Boolean,
    val busy: String?, val foreground: String?, val gateway: String?, val error: String? = null, val agent: Boolean = false
)

data class PlanStep(val skill: String?, val intent: String?, val why: String, val agent: String? = null)

enum class StepStatus { PENDING, RUNNING, VERIFIED, UNVERIFIED, FAILED, SKIPPED, CANCELLED }

data class StepState(
    val label: String, val status: StepStatus = StepStatus.PENDING, val attempts: Int = 0,
    val evidence: String = "", val reason: String = ""
)

data class ChatMessage(val fromUser: Boolean, val text: String, val steps: List<StepState> = emptyList())

data class Settings(
    val bridgeUrl: String = "http://127.0.0.1:8787",
    val retryLimit: Int = 1,
    val timeoutSec: Int = 120,
    val provider: String = "gemini",
    val model: String = "",
    val logging: Boolean = true,
    val strictVerify: Boolean = false,
    val agentFallback: Boolean = false
)
