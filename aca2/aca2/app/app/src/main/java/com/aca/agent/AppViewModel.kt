package com.aca.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SecureStore(app)

    val settings = MutableStateFlow(store.loadSettings())
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val running = MutableStateFlow(false)
    val health = MutableStateFlow<Health?>(null)
    val skills = MutableStateFlow<List<Skill>>(emptyList())
    val history = MutableStateFlow<List<TaskView>>(emptyList())
    val liveSteps = MutableStateFlow<List<StepState>>(emptyList())
    val pendingConfirm = MutableStateFlow<String?>(null)
    val providerStatus = MutableStateFlow("")
    val models = MutableStateFlow<List<String>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val skillsError = MutableStateFlow("")

    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null

    fun hasKey(provider: String) = store.secret("key_$provider").isNotBlank()
    fun bridgeTokenSet() = store.secret("bridge_token").isNotBlank()
    fun saveKey(provider: String, key: String) { store.putSecret("key_$provider", key); log("API key for $provider updated") }
    fun saveBridgeToken(t: String) { store.putSecret("bridge_token", t); log("bridge token updated") }
    fun saveSettings(s: Settings) { settings.value = s; store.saveSettings(s) }

    private fun bridge() = BridgeClient(settings.value.bridgeUrl, store.secret("bridge_token"))

    private fun log(line: String) {
        if (!settings.value.logging) return
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.update { (it + "$ts $line").takeLast(200) }          // never receives keys or tokens
    }

    fun refresh(deep: Boolean = false) = viewModelScope.launch {
        val h = withContext(Dispatchers.IO) { bridge().health(deep) }
        health.value = h
        if (h.reachable) {
            try {
                skills.value = withContext(Dispatchers.IO) { bridge().skills() }
                history.value = withContext(Dispatchers.IO) { bridge().history() }
                skillsError.value = ""
            } catch (e: BridgeClient.BridgeError) { skillsError.value = e.message ?: e.kind }
        } else log("bridge: ${h.error}")
    }

    fun testProvider() = viewModelScope.launch {
        val s = settings.value
        val p = Providers.byId(s.provider)
        providerStatus.value = "Testing ${p.label}..."
        try {
            val m = withContext(Dispatchers.IO) { p.listModels(store.secret("key_${p.id}")) }
            models.value = m
            providerStatus.value = "Connected: ${m.size} models available"
            log("provider ${p.id}: connected (${m.size} models)")
        } catch (e: ProviderException) {
            models.value = emptyList()
            providerStatus.value = e.message ?: "failed"
            log("provider ${p.id}: ${e.message}")
        }
    }

    private suspend fun requestConfirm(text: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        confirmation = d
        pendingConfirm.value = text
        return d.await()
    }

    fun resolveConfirm(ok: Boolean) { pendingConfirm.value = null; confirmation?.complete(ok) }

    private fun setLast(m: ChatMessage) = messages.update { if (it.isEmpty()) listOf(m) else it.dropLast(1) + m }

    fun cancel() { confirmation?.complete(false); pendingConfirm.value = null; job?.cancel() }

    fun send(text: String) {
        if (text.isBlank() || running.value) return
        messages.update { it + ChatMessage(true, text) + ChatMessage(false, "Planning...") }
        running.value = true
        liveSteps.value = emptyList()
        job = viewModelScope.launch {
            try {
                val s = settings.value
                val br = bridge()
                val h = withContext(Dispatchers.IO) { br.health(false) }
                health.value = h
                if (!h.reachable) { setLast(ChatMessage(false, "Engine unavailable: ${h.error}")); return@launch }
                if (!h.engine) { setLast(ChatMessage(false, "Bridge is up but the engine (run_skill) is missing in Termux.")); return@launch }
                val sk = withContext(Dispatchers.IO) { br.skills() }
                skills.value = sk
                val p = Providers.byId(s.provider)
                val (reply, plan) = withContext(Dispatchers.IO) {
                    Orchestrator.plan(p, store.secret("key_${p.id}"), s.model, text, sk)
                }
                val finalPlan = when {
                    plan.isNotEmpty() -> plan
                    s.agentFallback && h.agent -> listOf(PlanStep(null, null, "no learned skill matches", agent = text))
                    else -> {
                        val why = when {
                            !s.agentFallback -> "No learned skill matches. Enable Hermes fallback in Settings to let the agent discover it."
                            else -> "No learned skill matches, and the bridge was not started with --agent."
                        }
                        setLast(ChatMessage(false, reply.ifBlank { why } + if (reply.isBlank()) "" else "\n$why")); return@launch
                    }
                }
                log("plan: ${finalPlan.size} step(s)")
                setLast(ChatMessage(false, reply.ifBlank { "Executing plan" }, finalPlan.map { StepState(it.skill ?: it.agent?.let { a -> "hermes agent: $a" } ?: "intent: ${it.intent}") }))
                val result = Orchestrator.execute(br, finalPlan, sk, s, { requestConfirm(it) }) { st ->
                    liveSteps.value = st
                    messages.update { l -> if (l.isEmpty()) l else l.dropLast(1) + l.last().copy(steps = st) }
                }
                val finalText = Orchestrator.report(result)
                messages.update { it + ChatMessage(false, finalText) }
                log("done: $finalText")
                refresh()
            } catch (e: ProviderException) {
                setLast(ChatMessage(false, "Provider error: ${e.message}")); log("provider error: ${e.message}")
            } catch (e: BridgeClient.BridgeError) {
                setLast(ChatMessage(false, "Bridge error (${e.kind}): ${e.message}")); log("bridge error: ${e.kind}")
            } catch (e: CancellationException) {
                messages.update { it + ChatMessage(false, "Cancelled. The running engine task was asked to stop.") }
                throw e
            } finally {
                running.value = false
            }
        }
    }
}
