package dev.dshpm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dshpm.android.pm.GatewayConfig

/**
 * AND5-1 ④: 无 token 引导页 — token 为空时替代 5-Tab 主界面。此阶段
 * MainActivity 对 blank token 跳过 connect(零网络流量、零崩溃路径);
 * 填齐 host/port/token 保存即进入主界面并开始重连。
 */
@Composable
fun OnboardingScreen(config: GatewayConfig, onSave: (GatewayConfig) -> Unit) {
    var host by remember { mutableStateOf(config.host) }
    var port by remember { mutableStateOf(config.port) }
    var token by remember { mutableStateOf(config.token) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("欢迎使用 dsh-pm 移动观测台", style = MaterialTheme.typography.headlineSmall)
        Text(
            "尚未配置网关 token。请填写与 pm-host-service 同一 LAN 的网关地址与 " +
                "token(VOICE_GATEWAY_TOKEN); 保存后进入票板 / 席位 / 流程 / 语音。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("LAN host (如 192.168.3.196)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("port (默认 8765)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("token (VOICE_GATEWAY_TOKEN)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(GatewayConfig(host.trim(), port.trim(), token.trim())) },
            enabled = host.isNotBlank() && port.isNotBlank() && token.isNotBlank(),
        ) {
            Text("保存并进入")
        }
        Text(
            "说明: token 仅存本机 SharedPreferences, 不入日志; 单 token 网关并发 ≤2。",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
