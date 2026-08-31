package dev.dshpm.android.pm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PM read-face UI models + pure parsers over pm.res.data (op=tickets/fleet/flow).
 *
 * Wire shapes observed live from pm-host-service (2026-08-31, op endpoints on 127.0.0.1):
 * - tickets: {op, count, tickets:[{ticket_id,title,state,deps:"<json-string array>",lease_owner,refs,outcome,updated_at}]}
 * - fleet:   {op, count, seats:[{code,sessionId,role,node,preset,spawnedAt,status,session:{running,title,...}}], degraded, sessionJoined, note}
 * - flow:    {op, count, flows:[{flow,source,degraded,nodes:[{node_id,verb,title,state,attempts,result,events}], rollup:[...]}]}
 */

data class TicketRow(
    val ticketId: String,
    val title: String,
    val state: String,
    val deps: List<String>,
    val leaseOwner: String?,
    /** ISO stamp from the service, when present (drives within-group ordering). */
    val updatedAt: String? = null,
)

data class TicketGroup(val state: String, val tickets: List<TicketRow>) {
    val count: Int get() = tickets.size
}

data class FleetSeatRow(
    val code: String,
    val status: String?,
    val node: String?,
    val title: String?,
    val role: String?,
    val spawnedAt: String?,
)

data class FlowNodeRow(
    val nodeId: String,
    val verb: String?,
    val title: String?,
    val state: String?,
)

data class FlowRow(
    val flow: String,
    val source: String?,
    val degraded: Boolean,
    val nodes: List<FlowNodeRow>,
)

object PmParsing {

    /** Board attention priority (AND4-1): live work leads, settled masses sink. */
    val STATE_PRIORITY: List<String> =
        listOf("dispatched", "running", "blocked", "done", "merged", "rejected")

    fun parseTickets(data: JsonObject?): List<TicketGroup> {
        val rows = data?.get("tickets") as? JsonArray ?: return emptyList()
        val tickets = rows.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            TicketRow(
                ticketId = o.str("ticket_id") ?: "?",
                title = o.str("title") ?: "",
                state = o.str("state") ?: "?",
                deps = parseDepsString(o.str("deps")),
                leaseOwner = o.str("lease_owner"),
                updatedAt = o.str("updated_at"),
            )
        }
        return groupByState(tickets)
    }

    /**
     * Single-column mobile kanban (AND4-1): groups stack in a fixed attention
     * priority — dispatched → running → blocked → done → merged → rejected —
     * so live work leads the board and settled masses (done/merged) sink below
     * the fold. States outside the priority list (e.g. rolled-back) append
     * after, in first-appearance order.
     *
     * Within a group: rows with a usable `updated_at` lead, newest first; rows
     * without one trail in service order. Implemented as a total order — a
     * partial comparator ("compare only when both stamped") is NOT enough:
     * TimSort detects the initial run via adjacent pairs and an order-violating
     * equivalence chain (stamped≡unstamped≡stamped) leaves the array untouched.
     */
    fun groupByState(tickets: List<TicketRow>): List<TicketGroup> {
        val byState = tickets.groupBy { it.state }
        val prioritized = STATE_PRIORITY.filter(byState::containsKey)
        val rest = byState.keys.filter { it !in STATE_PRIORITY } // first-appearance order
        return (prioritized + rest).map { state ->
            TicketGroup(
                state,
                byState.getValue(state).sortedWith(
                    compareBy<TicketRow> { stampMs(it.updatedAt) == null } // stamped lead
                        .thenByDescending { stampMs(it.updatedAt) ?: 0L }, // newest first
                ),
            )
        }
    }

    /** Epoch-ms for an ISO-8601 stamp; null when absent/unparseable. */
    private fun stampMs(iso: String?): Long? =
        iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** `deps` arrives as a JSON *string* containing a JSON array — unwrap tolerantly. */
    fun parseDepsString(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            when (val el = Json.parseToJsonElement(raw)) {
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun parseFleetSeats(data: JsonObject?): List<FleetSeatRow> {
        val seats = data?.get("seats") as? JsonArray ?: return emptyList()
        return seats.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            FleetSeatRow(
                code = o.str("code") ?: "?",
                status = o.str("status"),
                node = o.str("node"),
                title = (o["session"] as? JsonObject)?.str("title"),
                role = o.str("role"),
                spawnedAt = o.str("spawnedAt"),
            )
        }
    }

    fun parseFleetDegraded(data: JsonObject?): Boolean = data?.bool("degraded") ?: false

    fun parseFleetNote(data: JsonObject?): String? = data?.str("note")?.takeIf { it.isNotBlank() }

    fun parseFlows(data: JsonObject?): List<FlowRow> {
        val flows = data?.get("flows") as? JsonArray ?: return emptyList()
        return flows.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            FlowRow(
                flow = o.str("flow") ?: "?",
                source = o.str("source"),
                degraded = o.bool("degraded") ?: false,
                nodes = (o["nodes"] as? JsonArray)?.mapNotNull { n ->
                    val no = n as? JsonObject ?: return@mapNotNull null
                    FlowNodeRow(
                        nodeId = no.str("node_id") ?: "?",
                        verb = no.str("verb"),
                        title = no.str("title"),
                        state = no.str("state"),
                    )
                }.orEmpty(),
            )
        }
    }

    /**
     * Relative duration for seat lastSeen/spawned stamps, e.g. "3m ago" / "2h ago".
     * Tolerant of null/garbage input (renders "—" instead of crashing).
     */
    fun relativeDuration(isoStamp: String?, nowMs: Long = System.currentTimeMillis()): String {
        if (isoStamp == null) return "—"
        val instant = runCatching { java.time.Instant.parse(isoStamp) }.getOrNull() ?: return "—"
        val seconds = (nowMs - instant.toEpochMilli()) / 1000
        if (seconds < 0) return "—"
        val s = when {
            seconds < 10 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
        return s
    }

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
