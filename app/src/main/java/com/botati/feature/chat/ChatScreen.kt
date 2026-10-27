package com.botati.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    botId: String,
    onBack: () -> Unit,
    vm: ChatViewModel = koinViewModel(parameters = { parametersOf(botId, null) }),
) {
    val bot by vm.bot.collectAsState()
    val messages by vm.messages.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val running by vm.running.collectAsState()
    val status by vm.statusLine.collectAsState()
    val approval by vm.pendingApproval.collectAsState()
    val contract by vm.pendingContract.collectAsState()
    val activeTask by vm.activeTask.collectAsState()

    var input by remember { mutableStateOf("") }
    var showTaskDialog by remember { mutableStateOf(false) }
    var taskInstruction by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachFile(uri)
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.attachTree(uri)
    }

    LaunchedEffect(messages.size, streaming) {
        val count = messages.size + if (streaming.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${bot?.icon ?: "🤖"} ${bot?.name ?: "…"}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    TextButton(onClick = { showTaskDialog = true }, enabled = !running) {
                        Text("🎯 تكليف بمهمة")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (activeTask != null && activeTask?.status == "RUNNING") {
                Card(
                    Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(
                        "مهمة تعمل: ${activeTask?.instruction?.take(80)} • الحالة: ${activeTask?.status}",
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            status?.let {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty() && streaming.isEmpty() && !running) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize().padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyChatState(
                                botName = bot?.name.orEmpty(),
                                onSuggestionSelected = { input = it },
                            )
                        }
                    }
                }
                items(messages) { m ->
                    when (m.role) {
                        "tool" -> ToolResultCard(m.toolName ?: "tool", m.content)
                        "assistant" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!m.toolCallsJson.isNullOrBlank()) {
                                    ToolCallsCard(m.toolCallsJson!!)
                                }
                                if (m.content.isNotBlank()) MessageBubble("assistant", m.content)
                            }
                        }
                        else -> MessageBubble(m.role, m.content)
                    }
                }
                if (streaming.isNotEmpty()) {
                    item { MessageBubble("assistant", streaming + " ▍") }
                }
                if (running && streaming.isEmpty()) {
                    item { Text("…يفكر", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { docLauncher.launch(arrayOf("*/*")) }, enabled = !running) {
                    Icon(Icons.Filled.Create, contentDescription = "إرفاق ملف")
                }
                IconButton(onClick = { treeLauncher.launch(null) }, enabled = !running) {
                    Icon(Icons.Filled.AddCircle, contentDescription = "منح مجلد")
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالة…") },
                    maxLines = 4,
                )
                if (running) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(Icons.Filled.Close, contentDescription = "إيقاف")
                    }
                } else {
                    IconButton(onClick = {
                        if (input.isNotBlank()) { vm.send(input); input = "" }
                    }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
                    }
                }
            }
        }
    }

    approval?.let { ap ->
        AlertDialog(
            onDismissRequest = { vm.answerApproval(false) },
            title = { Text("طلب موافقة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ap.decisionLabel, style = MaterialTheme.typography.bodySmall)
                    Text("الأداة: ${ap.call.name}", style = MaterialTheme.typography.titleSmall)
                    ap.call.target?.let { Text("الهدف: $it") }
                    Text(
                        if (ap.fullReview) ap.argsText else ap.argsText.take(300),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ap.fullReview) {
                        Text(
                            "فعل غير قابل للتراجع — راجع النص كاملًا قبل السماح.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { vm.answerApproval(true) }) { Text("اسمح مرة") } },
            dismissButton = { OutlinedButton(onClick = { vm.answerApproval(false) }) { Text("ارفض") } },
        )
    }

    contract?.let { pc ->
        val c = pc.contract
        AlertDialog(
            onDismissRequest = { vm.rejectContract() },
            title = { Text("عقد نطاق المهمة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("المهمة: ${pc.instruction}")
                    Text("الفعل: ${c.verb} • الأدوات: ${c.allowedTools.joinToString()}")
                    Text("الأهداف: threads=${c.targets.threadIds} files=${c.targets.fileUris} query=${c.targets.query}")
                    Text("إفصاح البيانات: ${c.dataDisclosure} • قفل المستلمين: ${c.recipientsLock}")
                    Text("المحظور: ${c.forbidden.joinToString()}")
                    Text("الحدود: ${c.maxSteps} خطوة • $${c.budgetUsd}")
                    Text("بصمة العقد: ${c.computeHash().take(16)}…", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "نقرة الاعتماد هي التفويض — داخل هذا النطاق فقط (§11).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { Button(onClick = { vm.approveContract() }) { Text("اعتماد وتنفيذ") } },
            dismissButton = { OutlinedButton(onClick = { vm.rejectContract() }) { Text("رفض") } },
        )
    }

    if (showTaskDialog) {
        AlertDialog(
            onDismissRequest = { showTaskDialog = false },
            title = { Text("تكليف بمهمة") },
            text = {
                OutlinedTextField(
                    value = taskInstruction, onValueChange = { taskInstruction = it },
                    label = { Text("صِف المهمة بدقة — صياغتها تحدد النطاق") },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    showTaskDialog = false
                    vm.proposeTask(taskInstruction)
                    taskInstruction = ""
                }) { Text("اقتراح عقد") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showTaskDialog = false }) { Text("إلغاء") }
            },
        )
    }
}

@Composable
private fun EmptyChatState(
    botName: String,
    onSuggestionSelected: (String) -> Unit,
) {
    val suggestions = listOf(
        "اشرح لي فكرة ببساطة",
        "ساعدني في كتابة رسالة",
        "لخّص لي النقاط الأساسية",
    )

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (botName.isBlank()) "ابدأ محادثة" else "ابدأ محادثة مع $botName",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "اكتب سؤالك، أو اختر اقتراحاً لإضافته إلى مربع الكتابة دون إرساله.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onSuggestionSelected(suggestion) },
                    label = { Text(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(role: String, content: String) {
    val isUser = role == "user"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(if (isUser) "أنت" else "البوت", style = MaterialTheme.typography.labelSmall)
            Text(content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToolCallsCard(toolCallsJson: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text("🔧 نداءات أدوات ${if (expanded) "▲" else "▼"}")
            }
            if (expanded) Text(toolCallsJson.take(1_000), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToolResultCard(toolName: String, content: String) {
    var expanded by remember { mutableStateOf(false) }
    val preview = content.replace("<tool_result>", "").replace("</tool_result>", "").trim()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(10.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text("↩ نتيجة $toolName ${if (expanded) "▲" else "▼"}")
            }
            Text(if (expanded) preview.take(2_000) else preview.take(160), style = MaterialTheme.typography.bodySmall)
        }
    }
}
