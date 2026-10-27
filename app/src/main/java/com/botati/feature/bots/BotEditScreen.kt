package com.botati.feature.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.botati.domain.tools.ToolRegistry
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotEditScreen(
    botId: String?,
    onDone: () -> Unit,
    vm: BotEditViewModel = koinViewModel(parameters = { parametersOf(botId) }),
) {
    val s by vm.state.collectAsState()
    val servers by vm.servers.collectAsState()
    val allTools by vm.allTools.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (botId == null) "بوت جديد" else "تعديل البوت") }) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.icon, onValueChange = { vm.update { st -> st.copy(icon = it) } },
                    label = { Text("أيقونة") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = s.name, onValueChange = { vm.update { st -> st.copy(name = it) } },
                    label = { Text("الاسم") }, modifier = Modifier.weight(3f), singleLine = true,
                )
            }
            OutlinedTextField(
                value = s.systemPrompt, onValueChange = { vm.update { st -> st.copy(systemPrompt = it) } },
                label = { Text("System Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 4,
            )
            Text("مزوّد الذكاء الاصطناعي", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("openrouter" to "OpenRouter", "alpme" to "Alpme").forEach { (id, label) ->
                    FilterChip(
                        selected = s.provider == id,
                        onClick = { vm.setProvider(id) },
                        label = { Text(label) },
                    )
                }
            }
            if (s.provider == "alpme") {
                Text("أضف مفتاح Alpme من تبويب الخزنة المشفّرة.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = s.model, onValueChange = { vm.update { st -> st.copy(model = it) } },
                label = { Text("معرّف الموديل (${if (s.provider == "alpme") "Alpme" else "OpenRouter"})") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Text("الحرارة: ${"%.2f".format(s.temperature)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = s.temperature, onValueChange = { vm.update { st -> st.copy(temperature = it) } },
                valueRange = 0f..2f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TASK_FREEDOM" to "حرية مهمة", "SUPERVISED" to "بإشراف").forEach { (v, label) ->
                    SuggestionChip(
                        onClick = { vm.update { st -> st.copy(autonomyMode = v) } },
                        label = { Text(if (s.autonomyMode == v) "● $label" else label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = s.maxStepsOverride,
                    onValueChange = { vm.update { st -> st.copy(maxStepsOverride = it) } },
                    label = { Text("حد خطوات (اختياري)") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = s.budgetOverride,
                    onValueChange = { vm.update { st -> st.copy(budgetOverride = it) } },
                    label = { Text("سقف $ (اختياري)") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }

            Text("الأدوات المدمجة والبحث", style = MaterialTheme.typography.titleSmall)
            ToolRegistry.builtins.forEach { meta ->
                ToolToggleRow(
                    label = meta.name, badge = "${meta.effect}/${meta.disclosure}",
                    checked = meta.name in s.allowedTools,
                    onToggle = { vm.toggleTool(meta.name) },
                )
            }

            Text("خوادم MCP المرتبطة", style = MaterialTheme.typography.titleSmall)
            if (servers.isEmpty()) {
                Text("لا خوادم بعد — أضفها من تبويب «خوادم MCP».", style = MaterialTheme.typography.bodySmall)
            }
            servers.forEach { server ->
                ToolToggleRow(
                    label = "${server.name} (${server.prefix}__…)",
                    badge = server.status,
                    checked = server.id in s.mcpServerIds,
                    onToggle = { vm.toggleServer(server.id) },
                )
                if (server.id in s.mcpServerIds) {
                    allTools.filter { it.serverId == server.id }.forEach { tool ->
                        ToolToggleRow(
                            label = "   ↳ ${tool.prefixedName}", badge = "${tool.effect}/${tool.disclosure}",
                            checked = tool.prefixedName in s.allowedTools,
                            onToggle = { vm.toggleTool(tool.prefixedName) },
                        )
                    }
                }
            }

            Button(
                onClick = { vm.save(onDone) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("حفظ") }
        }
    }
}

@Composable
private fun ToolToggleRow(label: String, badge: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        SuggestionChip(onClick = {}, label = { Text(badge) })
    }
}
