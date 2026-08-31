package dev.dshpm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgeDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.dshpm.android.pm.BoardController
import dev.dshpm.android.pm.GatewayConfig
import dev.dshpm.android.voice.VoiceController
import dev.dshpm.proto.ws.ConnectionState

private enum class Tab(val label: String, val icon: ImageVector) {
    TICKETS("票板", Icons.Filled.List),
    FLEET("席位", Icons.Filled.Place),
    FLOW("流程", Icons.Filled.Star),
    VOICE("语音", Icons.Filled.Phone),
    SETTINGS("设置", Icons.Filled.Settings),
}

/** Three PM views + settings, read-only, bottom-tab switched (spec-android §3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshPmApp(
    state: BoardController.UiState,
    config: GatewayConfig,
    nowMs: Long,
    onConfigSaved: (GatewayConfig) -> Unit,
    voiceState: VoiceController.VoiceState = VoiceController.VoiceState(),
    voiceHooks: VoiceHooks = VoiceHooks(true, {}, {}, {}, {}),
    onVoiceTabActive: (Boolean) -> Unit = {},
    rttMs: Long? = null,
) {
    // AND5-1 ④: 无 token → 引导页代替 5-Tab (无连接、无语音入口);
    // 保存 token 后 onConfigSaved 重连并进入主界面.
    if (config.token.isBlank()) {
        OnboardingScreen(config, onConfigSaved)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.TICKETS) }

    // AND4-2 ④: 语音 tab 生命周期——进入建会话, 退出优雅结束
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onVoiceTabActive(false) }
    }
    androidx.compose.runtime.LaunchedEffect(tab) {
        onVoiceTabActive(tab == Tab.VOICE)
    }
    Scaffold(
        topBar = { StatusBar(state, config) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.banner?.let { Banner(it) }
            when (tab) {
                Tab.TICKETS -> TicketsScreen(state)
                Tab.FLEET -> FleetScreen(state, nowMs)
                Tab.FLOW -> FlowScreen(state)
                Tab.VOICE -> VoiceScreen(voiceState, voiceHooks)
                Tab.SETTINGS -> SettingsScreen(config, onConfigSaved, rttMs)
            }
        }
    }
}

@Composable
private fun StatusBar(state: BoardController.UiState, config: GatewayConfig) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text("dsh-pm 移动观测台", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${config.host}:${config.port}", style = MaterialTheme.typography.labelSmall)
            ConnectionStateBadge(state.connection)
        }
    }
}

@Composable
fun ConnectionStateBadge(connection: ConnectionState) {
    val (color, label) = when (connection) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32) to "在线"
        ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> Color(0xFF9E9D24) to "连接中"
        ConnectionState.RECONNECT_WAIT -> Color(0xFFEF6C00) to "重连中"
        ConnectionState.CLOSED, ConnectionState.IDLE -> Color(0xFFC62828) to "离线"
    }
    Badge(containerColor = color) { Text(label) }
}

@Composable
fun Banner(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(Color(0x33EF6C00))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun EmptyState(headline: String, note: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
