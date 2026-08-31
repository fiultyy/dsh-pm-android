package dev.dshpm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dshpm.android.pm.GatewayConfig
import dev.dshpm.proto.ws.ConnectionState

/** 设置屏: LAN host/port/token 输入, 保存即重连 (spec-android §2 连接配置). */
@Composable
fun SettingsScreen(config: GatewayConfig, onSave: (GatewayConfig) -> Unit) {
    var host by remember { mutableStateOf(config.host) }
    var port by remember { mutableStateOf(config.port) }
    var token by remember { mutableStateOf(config.token) }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("网关连接", style = MaterialTheme.typography.titleMedium)
        Text(
            "模拟器访问宿主网关用 10.0.2.2(宿主 loopback 别名); 真机用 LAN IP(如 192.168.3.196)。token=VOICE_GATEWAY_TOKEN, 仅存本机不入日志。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it; saved = false },
            label = { Text("LAN host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it; saved = false },
            label = { Text("port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; saved = false },
            label = { Text("token (VOICE_GATEWAY_TOKEN)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onSave(GatewayConfig(host.trim(), port.trim(), token.trim()))
                saved = true
            },
            enabled = host.isNotBlank() && port.isNotBlank() && token.isNotBlank(),
        ) {
            Text("保存并重连")
        }
        if (saved) {
            Text("已保存 — 重连中, 见顶部状态条", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "状态: ${ConnectionState.entries.joinToString()} 见票板顶部徽标; 单 token 并发 ≤2(PC+手机同时在线时第三连接被拒属预期)。",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
