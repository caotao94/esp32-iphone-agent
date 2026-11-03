package com.example.esp32agent.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.esp32agent.android.AgentRuntime
import com.example.esp32agent.android.AppGraph
import com.example.esp32agent.android.agent.StepText
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.normalizeApiKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole skeleton UI: run a task, look after the board, set the model, read the log.
 * A run itself lives in the service; this only starts and watches it.
 */
@Composable
fun AppUi(graph: AppGraph, onStart: (String) -> Unit, onCalibrate: () -> Unit, onStop: () -> Unit, onScan: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Run", "Board", "Model", "Log")
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> RunTab(graph, onStart, onStop)
                1 -> BoardTab(graph, onCalibrate, onScan)
                2 -> ModelTab(graph)
                else -> LogTab()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// MARK: - Run

@Composable
private fun RunTab(graph: AppGraph, onStart: (String) -> Unit, onStop: () -> Unit) {
    val running by AgentRuntime.running.collectAsState()
    val doing by AgentRuntime.doing.collectAsState()
    val notice by AgentRuntime.notice.collectAsState()
    val plan by AgentRuntime.plan.collectAsState()
    val steps by AgentRuntime.steps.collectAsState()
    val pointer by AgentRuntime.pointer.collectAsState()
    val board by graph.board.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    val settings = remember { graph.settings }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
    ) {
        Section("Task") {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What should the agent do?") },
                placeholder = { Text("Open Settings and go to Wi-Fi") },
                minLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onStart(input.trim()) },
                    enabled = !running && input.isNotBlank(),
                ) { Text("Start") }
                OutlinedButton(onClick = onStop, enabled = running) { Text("Stop") }
            }
            Spacer(Modifier.height(8.dp))
            Checklist(board.connected, graph.capture.live, settings.hasModel, pointer)
            notice?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }

        if (running || doing.isNotEmpty()) {
            Section("Now") { Text(if (doing.isEmpty()) "working" else doing) }
        }

        if (plan.isNotEmpty()) {
            Section("Plan") { Text(plan, fontSize = 14.sp) }
        }

        if (steps.isNotEmpty()) {
            Section("Steps (${steps.size})") {
                steps.forEachIndexed { i, step ->
                    val action = step["action"]?.str
                    val changed = step["changed"]?.bool
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(StepText.symbol(action), modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text(StepText.line(step), fontSize = 14.sp)
                            if (changed == false) {
                                Text("the screen did not change", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Text("#${i + 1}  ${StepText.title(step)}", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Checklist(board: Boolean, capture: Boolean, model: Boolean, pointer: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (board) "✅" else "⬜", modifier = Modifier.padding(end = 6.dp))
            Text(if (board) "board connected" else "board not connected (Board tab)", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (model) "✅" else "⬜", modifier = Modifier.padding(end = 6.dp))
            Text(if (model) "model set" else "no model yet (Model tab)", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (pointer != null) "✅" else "⬜", modifier = Modifier.padding(end = 6.dp))
            Text(
                when (pointer) {
                    "absolute" -> "pointer: absolute (Android firmware)"
                    "relative" -> "pointer: relative (calibrated)"
                    else -> "pointer: checked when a run starts"
                },
                fontSize = 13.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ℹ️", modifier = Modifier.padding(end = 6.dp))
            Text(
                if (capture) "the capture is running" else "starting asks to share the entire screen",
                fontSize = 13.sp,
            )
        }
    }
}

// MARK: - Board

@Composable
private fun BoardTab(graph: AppGraph, onCalibrate: () -> Unit, onScan: () -> Unit) {
    val ui by graph.board.ui.collectAsState()
    var mode by remember { mutableStateOf(graph.settings.pointerMode) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Section("Board") {
            Line("Name", ui.name.ifEmpty { "-" })
            Line("Link", if (ui.connected) "ready" else if (ui.linking) "subscribing" else if (ui.connecting) "connecting" else "not connected")
            Line("USB", ui.status?.usb ?: "-")
            Line("Network", ui.status?.net ?: "-")
            Line("BLE", ui.status?.ble?.let { "mtu ${it.mtu ?: "-"} phy ${it.phy ?: "-"}" } ?: "-")
            Line("Uptime", ui.status?.up?.let { "${it / 1000}s (${ui.status?.rst ?: "?"})" } ?: "-")
            Line("Signal", ui.rssi?.let { "$it dBm" } ?: "-")
            if (ui.lastError.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(ui.lastError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, enabled = !ui.connected) { Text("Scan") }
                OutlinedButton(onClick = { graph.board.stopScan() }, enabled = ui.scanning) { Text("Stop") }
                OutlinedButton(
                    onClick = { scope.launch { graph.board.unpair() } },
                    enabled = ui.connected,
                ) { Text("Unpair") }
            }
        }

        if (ui.scanning || ui.found.isNotEmpty()) {
            Section("Found") {
                if (ui.found.isEmpty()) Text("looking for a board named “ESP32 Agent XXXX”...", fontSize = 13.sp)
                ui.found.forEach { found ->
                    TextButton(onClick = { graph.board.pair(found.address) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${found.name}   ${found.address}   ${found.rssi} dBm")
                    }
                }
            }
        }

        Section("Pointer") {
            Text(
                "An Android firmware build (hardware/flash.sh --android) points absolutely: no calibration, " +
                    "no drift. The stock build needs the relative pointer calibrated from the screen.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            listOf("auto" to "auto (ask the board)", "abs" to "absolute", "rel" to "relative")
                .forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            mode = value
                            graph.settings.pointerMode = value
                        }) { Text(if (mode == value) "● $label" else "○ $label") }
                    }
                }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onCalibrate) { Text("Calibrate the relative pointer") }
            Text(
                "Needs a screen capture, so Android asks to share the screen first; keep the screen still.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Model

@Composable
private fun ModelTab(graph: AppGraph) {
    val settings = remember { graph.settings }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var actor by remember { mutableStateOf(settings.actorModel) }
    var planner by remember { mutableStateOf(settings.plannerModel) }
    var maxSteps by remember { mutableStateOf(settings.maxSteps.toString()) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        settings.baseUrl = baseUrl
        settings.apiKey = apiKey
        settings.actorModel = actor
        settings.plannerModel = planner
        settings.maxSteps = maxSteps.toIntOrNull() ?: 40
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Section("Endpoint") {
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; save() },
                modifier = Modifier.fillMaxWidth(), label = { Text("Base URL") },
                placeholder = { Text("https://example.com/v1") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = normalizeApiKey(it); save() },
                modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = actor, onValueChange = { actor = it; save() },
                modifier = Modifier.fillMaxWidth(), label = { Text("Actor model (must accept images)") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = planner, onValueChange = { planner = it; save() },
                modifier = Modifier.fillMaxWidth(), label = { Text("Planner model (optional)") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = maxSteps, onValueChange = { maxSteps = it; save() },
                modifier = Modifier.fillMaxWidth(), label = { Text("Step limit") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    save()
                    busy = true
                    message = ""
                    scope.launch {
                        message = runCatching { graph.testModel() }
                            .getOrElse { "test failed: " + (it.message ?: it.javaClass.simpleName) }
                        busy = false
                    }
                }, enabled = !busy && baseUrl.isNotBlank() && actor.isNotBlank()) { Text("Test") }
                OutlinedButton(onClick = {
                    save()
                    busy = true
                    message = ""
                    scope.launch {
                        val list = runCatching { graph.listModels() }
                        models = list.getOrDefault(emptyList())
                        message = list.fold(
                            onSuccess = { "${it.size} models on this endpoint" },
                            onFailure = { "no model list: " + (it.message ?: it.javaClass.simpleName) },
                        )
                        busy = false
                    }
                }, enabled = !busy && baseUrl.isNotBlank()) { Text("List models") }
            }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        if (models.isNotEmpty()) {
            Section("Models on this endpoint") {
                models.forEach { id ->
                    TextButton(onClick = { actor = id; save() }, modifier = Modifier.fillMaxWidth()) {
                        Text(id, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// MARK: - Log

@Composable
private fun LogTab() {
    var lines by remember { mutableStateOf(DebugLog.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = DebugLog.snapshot()
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lines = DebugLog.snapshot() }) { Text("Refresh") }
            OutlinedButton(onClick = { DebugLog.clear(); lines = emptyList() }) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            lines.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}
