package com.botati.feature.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.botati.data.repo.BotTemplates
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(
    onOpenChat: (String) -> Unit,
    onEditBot: (String?) -> Unit,
    vm: BotsViewModel = koinViewModel(),
) {
    val bots by vm.bots.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("بوتاتي — بوتاتك") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "بوت جديد")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bots.isEmpty()) {
                item { Text("لا بوتات بعد — أنشئ أول بوت من الزر +", style = MaterialTheme.typography.bodyLarge) }
            }
            items(bots) { bot ->
                val cost by vm.monthCost(bot.id).collectAsState(initial = 0.0)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${bot.icon} ${bot.name}", style = MaterialTheme.typography.titleMedium)
                            Row {
                                IconButton(onClick = { onEditBot(bot.id) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "تعديل")
                                }
                                IconButton(onClick = { vm.delete(bot) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف")
                                }
                            }
                        }
                        Text("الموديل: ${bot.model}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "صرف هذا الشهر: $${"%.4f".format(cost)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onOpenChat(bot.id) }) { Text("فتح المحادثة ←") }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("بوت جديد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BotTemplates.all.forEachIndexed { i, t ->
                        TextButton(onClick = {
                            vm.createFromTemplate(i); showCreate = false
                        }) { Text("من قالب: ${t.icon} ${t.name}") }
                    }
                    TextButton(onClick = { showCreate = false; onEditBot(null) }) {
                        Text("بوت فارغ مخصص…")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCreate = false }) { Text("إغلاق") }
            },
        )
    }
}
