package com.botati.feature.audit

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(vm: AuditViewModel = koinViewModel()) {
    val entries by vm.entries.collectAsState()
    val bots by vm.bots.collectAsState()
    val filter by vm.filterBotId.collectAsState()
    val steps by vm.defaultSteps.collectAsState()
    val budget by vm.defaultBudget.collectAsState()
    val exported by vm.exported.collectAsState()
    val context = LocalContext.current

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = exported
        if (uri != null && json != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }
    }
    LaunchedEffect(exported) {
        if (exported != null) createDoc.launch("botati-bots-export.json")
    }

    Scaffold(topBar = { TopAppBar(title = { Text("التدقيق والإعدادات") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("السقوف الافتراضية (§9.4)", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                steps.toString(),
                                { it.toIntOrNull()?.let { v -> vm.defaultSteps.value = v } },
                                label = { Text("أقصى خطوات") }, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                budget, { vm.defaultBudget.value = it },
                                label = { Text("أقصى صرف $") }, modifier = Modifier.weight(1f),
                            )
                        }
                        Button(onClick = { vm.saveCeilings() }, modifier = Modifier.fillMaxWidth()) {
                            Text("حفظ السقوف")
                        }
                        Button(onClick = { vm.exportBotsJson() }, modifier = Modifier.fillMaxWidth()) {
                            Text("تصدير البوتات JSON (بلا أسرار)")
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SuggestionChip(
                        onClick = { vm.filterBotId.value = null },
                        label = { Text(if (filter == null) "● الكل" else "الكل") },
                    )
                    bots.forEach { b ->
                        SuggestionChip(
                            onClick = { vm.filterBotId.value = b.id },
                            label = { Text(if (filter == b.id) "● ${b.name}" else b.name) },
                        )
                    }
                }
            }
            if (entries.isEmpty()) {
                item { Text("لا سجلّات بعد.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(entries) { e ->
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "$time • ${e.tool} → ${e.decision} (${e.reasonCode})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "E=${e.effect} D=${e.disclosure} target=${e.target ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        e.resultSummary?.let {
                            Text(it.take(140), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
