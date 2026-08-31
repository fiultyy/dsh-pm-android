package dev.dshpm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dshpm.android.pm.BoardController
import dev.dshpm.android.pm.FlowRow

/** 流程: flow 折叠列表 → 节点状态表 node_id/state/verb (spec-android §3). */
@Composable
fun FlowScreen(state: BoardController.UiState) {
    if (!state.flowsLoaded) {
        EmptyState(
            if (state.connection == dev.dshpm.proto.ws.ConnectionState.CONNECTED) "加载流程…" else "流程待连接",
            "网关可达后经 pm.req(op=flow) 首拉",
        )
        return
    }
    if (state.flows.isEmpty()) {
        EmptyState("0 条流程", "flows/*/state.db 无流程状态或已降级")
        return
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.flows, key = { it.flow }) { flow ->
            FlowCard(flow, expanded[flow.flow] ?: false) { expanded[flow.flow] = !(expanded[flow.flow] ?: false) }
        }
    }
}

@Composable
private fun FlowCard(flow: FlowRow, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    (if (expanded) "▾ " else "▸ ") + flow.flow,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${flow.nodes.size} 节点${if (expanded) "" else " · 展开"}" +
                        (flow.source?.let { " · $it" } ?: "") +
                        (if (flow.degraded) " · 降级" else ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    flow.nodes.forEach { n ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(n.nodeId, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                n.verb?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                Text(
                                    n.state ?: "?",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (n.state) {
                                        "done" -> MaterialTheme.colorScheme.primary
                                        "failed" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
