package com.botati.feature.vault

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(vm: VaultViewModel = koinViewModel()) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window

    val orMasked by vm.openRouterMasked.collectAsState()
    val alpmeMasked by vm.alpmeMasked.collectAsState()
    val searchMasked by vm.searchMasked.collectAsState()
    val status by vm.status.collectAsState()
    var orKey by remember { mutableStateOf("") }
    var alpmeKey by remember { mutableStateOf("") }
    var searchKey by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    val protectScreenshots = orKey.isNotEmpty() || alpmeKey.isNotEmpty() || searchKey.isNotEmpty()
    val wasInitiallySecure = remember(window) {
        window?.let { it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 } ?: false
    }

    // Keys stay masked, and screenshots are blocked while a key is being entered.
    SideEffect {
        if (protectScreenshots) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    DisposableEffect(window) {
        onDispose {
            if (wasInitiallySecure) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("الخزنة — الأسرار") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("OpenRouter", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "الحالي: ${orMasked ?: "— غير مضبوط —"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        orKey, { orKey = it },
                        label = { Text("مفتاح جديد مخصص لهذا التطبيق (لا مفتاح Nabd)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = { vm.saveOpenRouterKey(orKey); orKey = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("حفظ") }
                    OutlinedButton(
                        onClick = { vm.testOpenRouterKey() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("اختبار الاتصال") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Alpme", style = MaterialTheme.typography.titleSmall)
                    Text("الحالي: ${alpmeMasked ?: "— غير مضبوط —"}", style = MaterialTheme.typography.bodySmall)
                    Text("Endpoint: https://alpme.com/v1", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        alpmeKey, { alpmeKey = it }, label = { Text("مفتاح Alpme") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = { vm.saveAlpmeKey(alpmeKey); alpmeKey = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("حفظ مفتاح Alpme") }
                    OutlinedButton(
                        onClick = { vm.testAlpmeKey() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("اختبار Alpme (طلب قصير قد يستهلك رموزًا)") }
                    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("بحث الويب (Tavily-compatible)", style = MaterialTheme.typography.titleSmall)
                    Text("الحالي: ${searchMasked ?: "— غير مضبوط —"}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        searchKey, { searchKey = it }, label = { Text("مفتاح البحث") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        endpoint, { endpoint = it },
                        label = { Text("نقطة النهاية (افتراضي https://api.tavily.com/search)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Button(
                        onClick = { vm.saveSearch(searchKey, endpoint); searchKey = "" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("حفظ إعدادات البحث") }
                }
            }
            Text(
                "الأسرار مشفّرة بـ Tink AEAD + مفتاح Android Keystore، ومستثناة من النسخ الاحتياطي.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
