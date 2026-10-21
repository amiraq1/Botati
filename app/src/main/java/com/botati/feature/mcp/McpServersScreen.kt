package com.botati.feature.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.botati.data.db.McpToolEntity
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(vm: McpViewModel = koinViewModel()) {
    val servers by vm.servers.collectAsState()
    val tools by vm.tools.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("خوادم MCP") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("خادم جديد", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(name, { name = it }, label = { Text("الاسم") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(url, { url = it }, label = { Text("الرابط (Streamable HTTP)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(
                            token, { token = it }, label = { Text("توكن (اختياري — يُحفظ بالخزنة)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Button(
                            onClick = { vm.addServer(name, url, token); name = ""; url = ""; token = "" },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        ) { Text("إضافة") }
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            items(servers) { server ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${server.name} — ${server.prefix}__*", style = MaterialTheme.typography.titleSmall)
                        Text(server.url, style = MaterialTheme.typography.bodySmall)
                        Text("الحالة: ${server.status}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.testAndSync(server) }, enabled = !busy) { Text("اختبار ومزامنة") }
                            OutlinedButton(onClick = { vm.delete(server) }) { Text("حذف") }
                        }
                        tools.filter { it.serverId == server.id }.forEach { tool ->
                            ToolClassificationRow(tool) { e, d -> vm.setClassification(tool, e, d) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolClassificationRow(
    tool: McpToolEntity,
    onChange: (effect: String, disclosure: String) -> Unit,
) {
    var effectMenu by remember { mutableStateOf(false) }
    var discMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(tool.prefixedName, style = MaterialTheme.typography.bodySmall)
        Row {
            Column {
                TextButton(onClick = { effectMenu = true }) { Text(tool.effect) }
                DropdownMenu(expanded = effectMenu, onDismissRequest = { effectMenu = false }) {
                    listOf("E0", "E1", "E2", "E3").forEach { e ->
                        DropdownMenuItem(text = { Text(e) },
                            onClick = { effectMenu = false; onChange(e, tool.disclosure) })
                    }
                }
            }
            Column {
                TextButton(onClick = { discMenu = true }) { Text(tool.disclosure) }
                DropdownMenu(expanded = discMenu, onDismissRequest = { discMenu = false }) {
                    listOf("D0", "D1", "D2").forEach { d ->
                        DropdownMenuItem(text = { Text(d) },
                            onClick = { discMenu = false; onChange(tool.effect, d) })
                    }
                }
            }
        }
    }
}
