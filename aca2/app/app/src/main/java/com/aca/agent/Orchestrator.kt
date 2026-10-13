package com.aca.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Plans with the LLM, executes step by step through the bridge, and reports only what the engine evidenced. */
object Orchestrator {
    const val MAX_STEPS = 6
    private val SENSITIVE = Regex("like|save|repost|comment|post|share|follow|delete|send|story|upload", RegexOption.IGNORE_CASE)

    private fun systemPrompt(skills: List<Skill>): String {
        val list = skills.filter { it.runnable }.joinToString("\n") {
            "- ${it.name}: app=${it.app}, target=${it.target}, steps=${it.steps}, sensitive=${it.needsConfirmation}"
        }
        return "You are the planner for an Android automation engine. Use ONLY the skills listed below, " +
            "or an \"intent\" text that the engine resolver understands (for example: open reels). " +
            "Never invent skill names. If the request cannot be done with these skills, return an empty steps list and say why in reply. " +
            "Return ONLY JSON: {\"reply\":\"short message\",\"steps\":[{\"skill\":\"<name>\",\"why\":\"...\"}]}. " +
            "Use \"intent\" instead of \"skill\" only if no skill fits. Max $MAX_STEPS steps.\nSkills:\n$list"
    }

    /** Returns the planner reply and the validated steps. Unknown skill names are dropped, never executed. */
    fun plan(provider: LlmProvider, key: String, model: String, request: String, skills: List<Skill>): Pair<String, List<PlanStep>> {
        val raw = provider.complete(key, model, systemPrompt(skills), request)
        val json = try {
            JSONObject(raw.substring(raw.indexOf("{"), raw.lastIndexOf("}") + 1))
        } catch (e: Exception) {
            throw ProviderException("Planner did not return valid JSON")
        }
        val arr = json.optJSONArray("steps")
        val known = skills.filter { it.runnable }.map { it.name }.toSet()
        val steps = ArrayList<PlanStep>()
        for (i in 0 until minOf(arr?.length() ?: 0, MAX_STEPS)) {
            val o = arr!!.getJSONObject(i)
            val sk = o.optString("skill").takeIf { it.isNotBlank() }
            val intent = o.optString("intent").takeIf { it.isNotBlank() }
            if (sk != null && sk in known) steps.add(PlanStep(sk, null, o.optString("why")))
            else if (sk == null && intent != null && intent.length <= 200) steps.add(PlanStep(null, intent, o.optString("why")))
        }
        return json.optString("reply") to steps
    }

    /**
     * Sequential execution. A step is VERIFIED only when the engine reports evidence "verified".
     * Multi-step skills evidence only "executed_unverified": continued unless strictVerify (documented exception).
     * Sensitive (non-idempotent) steps need user confirmation and are never retried automatically.
     */
    suspend fun execute(
        bridge: BridgeClient, steps: List<PlanStep>, skills: List<Skill>, s: Settings,
        confirm: suspend (String) -> Boolean, onUpdate: (List<StepState>) -> Unit
    ): List<StepState> {
        val byName = skills.associateBy { it.name }
        val st = steps.map { StepState(it.skill ?: it.agent?.let { a -> "hermes agent: $a" } ?: ("intent: " + it.intent)) }.toMutableList()
        fun push(i: Int, v: StepState) { st[i] = v; onUpdate(st.toList()) }
        var activeTask: String? = null
        try {
            for ((i, step) in steps.withIndex()) {
                val sensitive = step.agent != null || (step.skill?.let { byName[it]?.needsConfirmation } ?: false) ||
                    (step.intent?.let { SENSITIVE.containsMatchIn(it) } ?: false)
                var confirmed = false
                if (sensitive) {
                    confirmed = confirm("Allow this action?\n${st[i].label}")
                    if (!confirmed) {
                        push(i, st[i].copy(status = StepStatus.SKIPPED, reason = "declined by user"))
                        return markRest(st, i, onUpdate)
                    }
                }
                val maxAttempts = if (sensitive) 1 else s.retryLimit + 1
                var attempt = 0
                var done = false
                while (attempt < maxAttempts && !done) {
                    attempt++
                    push(i, st[i].copy(status = StepStatus.RUNNING, attempts = attempt))
                    val tv: TaskView = try {
                        val id = withContext(Dispatchers.IO) { bridge.submit(step.skill, step.intent, s.timeoutSec, confirmed, step.agent) }
                        activeTask = id
                        val deadline = System.currentTimeMillis() + (s.timeoutSec + 20) * 1000L
                        var t: TaskView
                        do {
                            delay(1000)
                            t = withContext(Dispatchers.IO) { bridge.task(id) }
                        } while (t.state == "running" && System.currentTimeMillis() < deadline)
                        activeTask = null
                        t
                    } catch (e: BridgeClient.BridgeError) {
                        push(i, st[i].copy(status = StepStatus.FAILED, reason = "${e.kind}: ${e.message}"))
                        return markRest(st, i, onUpdate)       // engine/bridge problem: stop, do not hammer
                    }
                    when {
                        tv.state == "success" && tv.evidence == "verified" -> {
                            push(i, st[i].copy(status = StepStatus.VERIFIED, evidence = "engine verified the target is selected"))
                            done = true
                        }
                        tv.state == "success" -> {
                            push(i, st[i].copy(status = StepStatus.UNVERIFIED, evidence = if (tv.evidence == "agent_reported") "Hermes agent reported completion; NOT independently verified" else "clicks executed; engine did not verify the end state"))
                            if (s.strictVerify) return markRest(st, i, onUpdate)
                            done = true
                        }
                        tv.state == "cancelled" -> {
                            push(i, st[i].copy(status = StepStatus.CANCELLED, reason = tv.reason))
                            return markRest(st, i, onUpdate)
                        }
                        else -> {
                            push(i, st[i].copy(status = StepStatus.FAILED, reason = "${tv.state}: ${tv.reason}"))
                            if (attempt < maxAttempts) {       // bounded recovery: gather fresh evidence first
                                val h = withContext(Dispatchers.IO) { bridge.health(true) }
                                push(i, st[i].copy(status = StepStatus.RUNNING, attempts = attempt,
                                    reason = "retrying; foreground=${h.foreground ?: "unknown"}, gateway=${h.gateway ?: "unknown"}"))
                                delay(1500)
                            }
                        }
                    }
                }
                if (!done) return markRest(st, i, onUpdate)     // retry limit reached: stop honestly
            }
        } catch (e: CancellationException) {
            activeTask?.let { id -> withContext(NonCancellable + Dispatchers.IO) { bridge.cancel(id) } }
            throw e
        }
        return st
    }

    private fun markRest(st: MutableList<StepState>, from: Int, onUpdate: (List<StepState>) -> Unit): List<StepState> {
        for (j in from + 1 until st.size) st[j] = st[j].copy(status = StepStatus.SKIPPED, reason = "not run: earlier step did not complete")
        onUpdate(st.toList())
        return st
    }

    fun report(st: List<StepState>): String {
        val ok = st.count { it.status == StepStatus.VERIFIED }
        val un = st.count { it.status == StepStatus.UNVERIFIED }
        val bad = st.count { it.status == StepStatus.FAILED || it.status == StepStatus.CANCELLED }
        val skipped = st.count { it.status == StepStatus.SKIPPED }
        return "Verified $ok, executed-unverified $un, failed/cancelled $bad, not run $skipped (of ${st.size})."
    }
}
