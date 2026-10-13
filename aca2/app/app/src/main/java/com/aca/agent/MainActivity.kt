package com.aca.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ACATheme {
                var tab by remember { mutableIntStateOf(0) }
                val confirm by vm.pendingConfirm.collectAsState()
                val tabs: List<Pair<String, ImageVector>> = listOf(
                    "Chat" to Icons.Default.Chat, "Dashboard" to Icons.Default.Dashboard,
                    "Skills" to Icons.Default.Extension, "AI" to Icons.Default.Cloud, "Settings" to Icons.Default.Settings
                )
                Scaffold(
                    containerColor = Charcoal,
                    bottomBar = {
                        NavigationBar(containerColor = Surface1) {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(icon, label) }, label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Surface2)
                                )
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> ChatScreen(vm)
                            1 -> DashboardScreen(vm)
                            2 -> SkillsScreen(vm)
                            3 -> ProvidersScreen(vm)
                            else -> SettingsScreen(vm)
                        }
                    }
                }
                if (confirm != null) {
                    AlertDialog(
                        onDismissRequest = { vm.resolveConfirm(false) },
                        title = { Text("Confirm action") },
                        text = { Text(confirm ?: "") },
                        confirmButton = { TextButton({ vm.resolveConfirm(true) }) { Text("Allow") } },
                        dismissButton = { TextButton({ vm.resolveConfirm(false) }) { Text("Decline") } }
                    )
                }
            }
        }
    }
}
