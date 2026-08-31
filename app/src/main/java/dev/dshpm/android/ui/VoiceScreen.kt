package dev.dshpm.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import dev.dshpm.android.voice.VoiceController
import dev.dshpm.proto.ws.ConnectionState

/**
 * 语音页 (AND4-2): PTT 按住说话 + 回合状态 + head 选择行 + 回合流水。
 * RECORD_AUDIO 被拒 → 显式空态 + 说明 (不闪退、不黑盒)。
 */
@Composable
fun VoiceScreen(state: VoiceController.VoiceState, hooks: VoiceHooks) {
    if (!hooks.micPermissionGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp2()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🎤 需要麦克风权限", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp2()))
            Text(
                "语音页依赖麦克风采集(RECORD_AUDIO)。当前权限被拒——请到系统设置授权后重进语音页。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp2()))
            Button(onClick = hooks.requestPermission) { Text("重新请求权限") }
        }
        return
    }

    // AND4-3 ①: PTT-held wins the main status; barge-in events stay in the transcript
    val (icon, label) = dev.dshpm.android.voice.VoiceStatus.mainVisual(
        capturing = state.ptt == VoiceController.PttState.CAPTURING,
        phase = state.phase,
    )
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp2())) {
        Spacer(Modifier.height(8.dp2()))

        // head 选择行 (v1: head.list 渲染 + head.switch 单选)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp2()),
        ) {
            if (state.heads.isEmpty()) {
                AssistChip(onClick = {}, label = { Text("head 加载中…") })
            }
            for (h in state.heads) {
                FilterChip(
                    selected = h.name == state.activeHead,
                    onClick = { hooks.switchHead(h.name) },
                    label = { Text(if (h.name == state.activeHead) "● ${h.label}" else h.label) },
                )
            }
        }
        state.switchNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp2()))

        // 会话状态行
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp2())) {
            Text(icon, style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "↓帧 ${state.downlinkFrames} · ${state.downlinkBytes}B" +
                        (if (state.downlinkMuteDropped > 0) " · 弃${state.downlinkMuteDropped}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (state.session) {
                        VoiceController.SessionState.IDLE -> "会话未开始"
                        VoiceController.SessionState.STARTING -> "会话建立中…"
                        VoiceController.SessionState.LIVE -> "在线 (${state.sessionId ?: "?"})"
                        VoiceController.SessionState.ENDED -> "会话已结束"
                    } + if (state.upstreamBlocks > 0) " · 上行 ${state.upstreamBlocks} 块" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let {
            Text(
                "⚠ $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp2()))

        // 回合流水
        val listState = rememberLazyListState()
        LaunchedEffect(state.transcript.size) {
            if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.size - 1)
        }
        Box(modifier = Modifier.weight(1f)) {
            if (state.transcript.isEmpty()) {
                Text(
                    "按住下方按钮说话; 松开结束。回合事件(head.turn)将在此显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp2())) {
                    items(state.transcript) { line ->
                        val li = dev.dshpm.android.voice.VoiceStatus.turnIcon(line.phase)
                        Text(
                            "$li ${line.detail ?: line.phase ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp2()))

        // PTT: 按住采集, 松开发送 (无 mic 帧——本地采集闸, tk 同款)
        val capturing = state.ptt == VoiceController.PttState.CAPTURING
        Box(
            modifier = Modifier.fillMaxWidth().height(96.dp2()).padding(bottom = 12.dp2()),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    capturing -> MaterialTheme.colorScheme.error
                    state.session == VoiceController.SessionState.LIVE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .size(88.dp2())
                    .pointerInput(state.session) {
                        detectTapGestures(
                            onPress = {
                                hooks.pttDown()
                                try {
                                    awaitRelease()
                                } finally {
                                    hooks.pttUp()
                                }
                            },
                        )
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (capturing) "松开\n结束" else "按住\n说话",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.session == VoiceController.SessionState.LIVE || capturing)
                            Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** UI → controller 桥 (保持 Composable 无直接逻辑依赖). */
data class VoiceHooks(
    val micPermissionGranted: Boolean,
    val requestPermission: () -> Unit,
    val pttDown: () -> Unit,
    val pttUp: () -> Unit,
    val switchHead: (String) -> Unit,
)

/** Local dp helper (avoids importing ui.unit repeatedly in call sites above). */
private fun Int.dp2() = androidx.compose.ui.unit.Dp(this.toFloat())
