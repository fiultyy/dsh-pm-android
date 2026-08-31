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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dshpm.android.pm.BoardController
import dev.dshpm.android.pm.TicketGroup

/** 票板: 单列堆叠移动版 kanban, 按 state 分组+计数 (spec-android §3). */
@Composable
fun TicketsScreen(state: BoardController.UiState) {
    if (!state.ticketsLoaded) {
        EmptyState(
            if (state.connection == dev.dshpm.proto.ws.ConnectionState.CONNECTED) "加载票面…" else "票面待连接",
            "网关可达后经 pm.req(op=tickets) 首拉",
        )
        return
    }
    if (state.ticketGroups.isEmpty()) {
        EmptyState("0 张票", "ledger 无票或降级 — 见顶部横幅")
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.ticketGroups.forEach { group ->
            item(key = "g-${group.state}") { GroupHeader(group) }
            items(group.tickets, key = { "t-${it.ticketId}" }) { TicketCard(it) }
        }
    }
}

@Composable
private fun GroupHeader(group: TicketGroup) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(group.state, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("${group.count} 张", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun TicketCard(ticket: dev.dshpm.android.pm.TicketRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ticket.ticketId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                ticket.leaseOwner?.let { Text("lease: $it", style = MaterialTheme.typography.labelSmall) }
            }
            Text(ticket.title, style = MaterialTheme.typography.bodySmall)
            if (ticket.deps.isNotEmpty()) {
                Text("deps: ${ticket.deps.joinToString(" ")}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
