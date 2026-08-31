package dev.dshpm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dshpm.android.pm.BoardController
import dev.dshpm.android.pm.PmParsing

/** 席位舰: 卡片列表 code/status/node/title + 相对时长 (spec-android §3). */
@Composable
fun FleetScreen(state: BoardController.UiState, nowMs: Long) {
    if (!state.fleetLoaded) {
        EmptyState(
            if (state.connection == dev.dshpm.proto.ws.ConnectionState.CONNECTED) "加载席位…" else "席位待连接",
            "网关可达后经 pm.req(op=fleet) 首拉",
        )
        return
    }
    if (state.fleetSeats.isEmpty()) {
        // spec §3 验收 1: 席位 0 时显式空态 + note
        EmptyState("0 席在线", state.fleetNote ?: "fleet.json 无在册席位或已降级")
        return
    }
    Column {
        if (state.fleetDegraded) {
            Banner("fleet 降级(dsh join 不可达): 纯席位视图${state.fleetNote?.let { " · $it" } ?: ""}")
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.fleetSeats, key = { it.code }) { seat ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(seat.code, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            seat.status?.let {
                                Text(
                                    "[$it]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (it == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                            seat.node?.let { Text("node $it", style = MaterialTheme.typography.labelSmall) }
                        }
                        seat.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            seat.role?.let { Text("role $it", style = MaterialTheme.typography.labelSmall) }
                            Text(
                                "spawned ${PmParsing.relativeDuration(seat.spawnedAt, nowMs)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
