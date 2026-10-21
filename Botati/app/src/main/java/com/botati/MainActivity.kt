package com.botati

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.botati.crash.CrashReporter
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.botati.feature.audit.AuditScreen
import com.botati.feature.bots.BotEditScreen
import com.botati.feature.bots.BotsScreen
import com.botati.feature.chat.ChatScreen
import com.botati.feature.mcp.McpServersScreen
import com.botati.feature.vault.VaultScreen
import com.botati.ui.theme.BotatiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.1.1 diagnostic build: if the crash reporter captured a report
        // (fatal crash or non-fatal startup failure), show it in a PLAIN
        // framework screen — no Compose/Koin/Room in this path, so the trace
        // stays reachable even when the thing that broke is the UI stack
        // itself. Only after the user clears it do we take the normal path.
        if (CrashReporter.hasReport(this)) {
            showCrashScreen()
            return
        }
        startComposeContent()
    }

    private fun showCrashScreen() {
        val trace = CrashReporter.readDiagnostics(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(TextView(this).apply {
            text = "⚠ تقرير انهيار — Botati"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "التطبيق واجه خطأ. شارك هذا التقرير كاملًا حتى يُصلح السبب، " +
                "أو امسحه وتابع (قد يعود الخطأ إذا لم يُصلح)."
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val traceView = TextView(this).apply {
            text = trace
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            addView(traceView)
        }
        root.addView(scroll)
        root.addView(Button(this).apply {
            text = "مشاركة التقرير"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Botati crash report")
                    putExtra(Intent.EXTRA_TEXT, trace)
                }
                startActivity(Intent.createChooser(send, "مشاركة التقرير"))
            }
        })
        root.addView(Button(this).apply {
            text = "مسح التقرير والمتابعة"
            setOnClickListener {
                CrashReporter.clear(this@MainActivity)
                recreate()
            }
        })
        setContentView(root)
    }

    private fun startComposeContent() {
        setContent {
            BotatiTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val dest = entry?.destination
                val topLevel = listOf(
                    Triple("bots", "البوتات", Icons.Filled.Face),
                    Triple("servers", "خوادم MCP", Icons.Filled.Build),
                    Triple("vault", "الخزنة", Icons.Filled.Lock),
                    Triple("audit", "التدقيق", Icons.Filled.List),
                )
                val showBar = topLevel.any { it.first == dest?.route }
                Scaffold(
                    bottomBar = {
                        if (showBar) {
                            NavigationBar {
                                topLevel.forEach { (route, label, icon) ->
                                    NavigationBarItem(
                                        selected = dest?.hierarchy?.any { it.route == route } == true,
                                        onClick = {
                                            nav.navigate(route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = label) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(nav, startDestination = "bots", modifier = Modifier.padding(padding)) {
                        composable("bots") {
                            BotsScreen(
                                onOpenChat = { botId -> nav.navigate("chat/$botId") },
                                onEditBot = { botId -> nav.navigate("edit/${botId ?: "new"}") },
                            )
                        }
                        composable("edit/{botId}") { back ->
                            val id = back.arguments?.getString("botId")?.takeIf { it != "new" }
                            BotEditScreen(botId = id, onDone = { nav.popBackStack() })
                        }
                        composable("chat/{botId}") { back ->
                            val botId = back.arguments?.getString("botId")!!
                            ChatScreen(botId = botId, onBack = { nav.popBackStack() })
                        }
                        composable("servers") { McpServersScreen() }
                        composable("vault") { VaultScreen() }
                        composable("audit") { AuditScreen() }
                    }
                }
            }
        }
    }
}
