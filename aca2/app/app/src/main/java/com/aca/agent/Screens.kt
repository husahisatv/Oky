package com.aca.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
private fun Pill(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Purple)
            content()
        }
    }
}

private fun statusColor(s: StepStatus) = when (s) {
    StepStatus.VERIFIED -> Ok
    StepStatus.UNVERIFIED -> Warn
    StepStatus.FAILED, StepStatus.CANCELLED -> Bad
    StepStatus.RUNNING -> Blue
    else -> Color.Gray
}

@Composable
private fun StepRow(s: StepState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(s.status.name.lowercase(), statusColor(s.status))
            Text(s.label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            if (s.attempts > 1) Text("x${s.attempts}", color = Warn, fontSize = 12.sp)
        }
        val detail = listOf(s.evidence, s.reason).filter { it.isNotBlank() }.joinToString(" | ")
        if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChatScreen(vm: AppViewModel) {
    val msgs by vm.messages.collectAsState()
    val running by vm.running.collectAsState()
    val health by vm.health.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.steps) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Android Control Agent", style = MaterialTheme.typography.titleLarge)
            val h = health
            when {
                h == null -> Pill("checking", Color.Gray)
                !h.reachable -> Pill("engine offline", Bad)
                !h.engine -> Pill("engine missing", Bad)
                running -> Pill("running", Blue)
                else -> Pill("connected", Ok)
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            if (msgs.isEmpty()) item {
                Text("Describe a task in natural language. The planner only uses skills that exist in your engine, " +
                    "and a step is reported as verified only with engine evidence.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 320.dp)
                        .background(if (m.fromUser) Purple.copy(alpha = 0.25f) else Surface2, RoundedCornerShape(16.dp))
                        .padding(12.dp).animateContentSize()) {
                        Text(m.text)
                        m.steps.forEach { StepRow(it) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Task, e.g. open reels") },
                maxLines = 4, shape = RoundedCornerShape(24.dp))
            if (running) IconButton({ vm.cancel() }) { Icon(Icons.Default.Stop, "Cancel", tint = Bad) }
            else IconButton({ vm.send(input); input = "" }, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Purple)
            }
        }
    }
}

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val h by vm.health.collectAsState()
    val live by vm.liveSteps.collectAsState()
    val hist by vm.history.collectAsState()
    val running by vm.running.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(5000) } }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel("Engine connection") {
                val x = h
                if (x == null) Text("checking...") else {
                    Text("Bridge: " + if (x.reachable) "reachable" else "UNREACHABLE", color = if (x.reachable) Ok else Bad)
                    if (!x.reachable) Text(x.error ?: "", fontSize = 12.sp)
                    Text("run_skill present: ${x.engine}")
                    Text("rish present: ${x.rish}   .android_control.sh: ${x.controlSh}")
                    Text("Shizuku gateway: ${x.gateway ?: "not probed (press Probe)"}")
                    Text("Foreground app: ${x.foreground ?: "unknown"}")
                    if (x.busy != null) Text("Engine busy with task ${x.busy}", color = Warn)
                }
                OutlinedButton({ vm.refresh(true) }) { Text("Probe gateway + foreground") }
            }
        }
        item {
            Panel("Active task") {
                if (!running && live.isEmpty()) Text("No active task", color = MaterialTheme.colorScheme.onSurfaceVariant)
                live.forEach { StepRow(it) }
            }
        }
        item { Text("Recent tasks (engine)", style = MaterialTheme.typography.titleSmall, color = Purple) }
        items(hist) { t -> TaskCard(t) }
    }
}

@Composable
private fun TaskCard(t: TaskView) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(t.state, when (t.state) { "success" -> Ok; "running" -> Blue; "cancelled", "timeout" -> Warn; else -> Bad })
                if (t.state == "success") Pill(t.evidence, if (t.evidence == "verified") Ok else Warn)
            }
            Text(t.skill ?: t.intent ?: "?", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            if (t.reason.isNotBlank()) Text("reason: ${t.reason}", fontSize = 12.sp, color = Bad)
            if (t.output.isNotEmpty()) Text(t.output.takeLast(4).joinToString("\n"), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SkillsScreen(vm: AppViewModel) {
    val skills by vm.skills.collectAsState()
    val hist by vm.history.collectAsState()
    val err by vm.skillsError.collectAsState()
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab, containerColor = Charcoal) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Skills (${skills.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("History (${hist.size})") })
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (err.isNotBlank()) item { Text(err, color = Bad) }
            if (tab == 0) {
                if (skills.isEmpty() && err.isBlank()) item { Text("No skills reported by the engine.") }
                items(skills) { s ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            Text("${s.app} - ${s.control} - ${s.steps} step(s)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(if (s.runnable) "runnable" else "not runnable", if (s.runnable) Ok else Bad)
                                if (s.needsConfirmation) Pill("asks confirmation", Warn)
                                Pill("evidence: ${s.evidence}", if (s.evidence == "verified") Ok else Color.Gray)
                            }
                            if (s.note.isNotBlank()) Text(s.note, fontSize = 12.sp, color = Warn)
                        }
                    }
                }
            } else items(hist) { TaskCard(it) }
        }
    }
}

@Composable
fun ProvidersScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val status by vm.providerStatus.collectAsState()
    val models by vm.models.collectAsState()
    var key by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI providers", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Providers.all.forEach { p ->
                FilterChip(s.provider == p.id, { vm.saveSettings(s.copy(provider = p.id, model = "")); key = "" }, { Text(p.label) })
            }
        }
        Panel("API key") {
            Text(if (vm.hasKey(s.provider)) "A key is stored (encrypted). Enter a new one to replace it." else "No key stored.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button({ vm.saveKey(s.provider, key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
        }
        Panel("Connection and model") {
            Button({ vm.testProvider() }) { Text("Test connection and load models") }
            if (status.isNotBlank()) Text(status, color = if (status.startsWith("Connected")) Ok else if (status.startsWith("Testing")) Blue else Bad)
            OutlinedTextField(s.model, { vm.saveSettings(s.copy(model = it)) }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
            if (models.isNotEmpty()) {
                Box {
                    OutlinedButton({ menu = true }) { Text("Choose from ${models.size} models") }
                    DropdownMenu(menu, { menu = false }) {
                        models.take(150).forEach { m -> DropdownMenuItem({ Text(m, fontSize = 13.sp) }, { vm.saveSettings(s.copy(model = m)); menu = false }) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val logs by vm.logs.collectAsState()
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Panel("Engine bridge (Termux)") {
            OutlinedTextField(s.bridgeUrl, { vm.saveSettings(s.copy(bridgeUrl = it.trim())) }, Modifier.fillMaxWidth(),
                label = { Text("Bridge URL (loopback only)") }, singleLine = true)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bridge token") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Text(if (vm.bridgeTokenSet()) "A token is stored (encrypted)." else "No token stored. Read it in Termux: cat ~/.android_memory/bridge_token",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ vm.saveBridgeToken(token); token = ""; vm.refresh(true) }, enabled = token.isNotBlank()) { Text("Save token and test") }
        }
        Panel("Execution") {
            Stepper("Retry limit (per step)", s.retryLimit, 0, 3) { vm.saveSettings(s.copy(retryLimit = it)) }
            Stepper("Task timeout (sec)", s.timeoutSec, 30, 600, 30) { vm.saveSettings(s.copy(timeoutSec = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.strictVerify, { vm.saveSettings(s.copy(strictVerify = it)) })
                Text("  Stop when a step is not verified by the engine")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.agentFallback, { vm.saveSettings(s.copy(agentFallback = it)) })
                Text("  Hermes fallback for requests with no learned skill (asks confirmation; bridge needs --agent)")
            }
        }
        Panel("Diagnostics") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(s.logging, { vm.saveSettings(s.copy(logging = it)) }); Text("  Keep in-memory log (never contains keys)")
            }
            Text(if (logs.isEmpty()) "(empty)" else logs.takeLast(40).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ onChange((value - step).coerceAtLeast(min)) }, Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("-") }
            Text("  $value  ")
            OutlinedButton({ onChange((value + step).coerceAtMost(max)) }, Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
    }
}
