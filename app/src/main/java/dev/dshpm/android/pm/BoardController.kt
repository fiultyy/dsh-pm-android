package dev.dshpm.android.pm

import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.PmEvent
import dev.dshpm.proto.frame.PmReq
import dev.dshpm.proto.frame.PmRes
import dev.dshpm.proto.frame.PmSub
import dev.dshpm.proto.frame.WsFrame
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.GatewayListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM board controller: the "view-model" for the three PM views.
 *
 * - Owns NO transport: [sendPort] is injected (production binds it to
 *   [dev.dshpm.proto.ws.VoiceGatewayClient.send], tests capture frames).
 *   UI therefore never touches the network directly (AND3-1 red line).
 * - pm.req correlation: monotonically increasing string ids, never reused
 *   across reconnects (spec-android §2 客户端义务).
 * - pm.event = change SIGNAL (kind/source/path; no data body) → debounced
 *   re-pull of the matching op. Duplicate signals are already suppressed by
 *   the client's (source,msgid) dedup window (AND2-3).
 * - pm_sub_failed / pm_sub_ended → auto re-subscribe (spec §2 错误面).
 *
 * Read-only PM face: this class NEVER issues write frames.
 */
class BoardController(
    private val sendPort: (ClientFrame) -> Unit,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit = { _, a -> a() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : GatewayListener {

    data class UiState(
        val connection: ConnectionState = ConnectionState.IDLE,
        val ticketsLoaded: Boolean = false,
        val ticketGroups: List<TicketGroup> = emptyList(),
        val fleetLoaded: Boolean = false,
        val fleetSeats: List<FleetSeatRow> = emptyList(),
        val fleetDegraded: Boolean = false,
        val fleetNote: String? = null,
        val flowsLoaded: Boolean = false,
        val flows: List<FlowRow> = emptyList(),
        val lastEventKind: String? = null,
        val banner: String? = null,
        val lastUpdateMs: Long = 0L,
    )

    @Volatile
    var uiState: UiState = UiState()
        private set

    /** Invoked after every state mutation (production bridges into Compose on main). */
    @Volatile
    var onChange: ((UiState) -> Unit)? = null

    /** Re-bind the send port (tests use this to attach a live client after construction). */
    @Volatile
    private var send: (ClientFrame) -> Unit = sendPort

    fun rebindSendPort(port: (ClientFrame) -> Unit) {
        send = port
    }

    private val idCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, String>() // pm.req id → op
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private var subscribed = false

    fun nextId(): String = idCounter.incrementAndGet().toString()

    // ------------------------------------------------------------------ pulls

    fun pullAll() {
        pull(OP_TICKETS)
        pull(OP_FLEET)
        pull(OP_FLOW)
    }

    fun pull(op: String) {
        val id = nextId()
        pending[id] = op
        safeSend(PmReq(id = JsonPrimitive(id), op = op))
    }

    fun subscribe() {
        val id = nextId()
        subscribed = true
        safeSend(PmSub(id = JsonPrimitive(id), kinds = SUB_KINDS))
    }

    // ------------------------------------------------------- GatewayListener

    override fun onState(state: ConnectionState) {
        update(uiState.copy(connection = state))
        when (state) {
            ConnectionState.CONNECTED -> {
                subscribed = false
                // Clear any stale disconnect/error banner — this connection cycle is healthy.
                update(uiState.copy(banner = null))
                pullAll()
                subscribe()
            }
            ConnectionState.RECONNECT_WAIT ->
                update(uiState.copy(banner = "连接断开, 自动重连中…"))
            else -> Unit
        }
    }

    override fun onFrame(frame: WsFrame) {
        when (frame) {
            is PmRes -> onPmRes(frame)
            is PmEvent -> onPmEvent(frame)
            is ErrorFrame -> onErrorFrame(frame)
            else -> Unit // typed acks/unknown/malformed — nothing board-specific
        }
    }

    override fun onError(error: Throwable) {
        update(uiState.copy(banner = "传输错误: ${error.message ?: error.javaClass.simpleName}"))
    }

    // ------------------------------------------------------------- internals

    private fun onPmRes(res: PmRes) {
        val id = (res.id as? JsonPrimitive)?.content ?: return
        val op = pending.remove(id) ?: return
        val err = res.error
        if (err != null) {
            update(uiState.copy(banner = "pm.$op: ${err.code} ${err.message ?: ""}".trim()))
            return
        }
        val data = res.data as? JsonObject
        val s = uiState
        when (op) {
            OP_TICKETS -> update(
                s.copy(
                    ticketsLoaded = true,
                    ticketGroups = PmParsing.parseTickets(data),
                    lastUpdateMs = nowMs(),
                ),
            )
            OP_FLEET -> update(
                s.copy(
                    fleetLoaded = true,
                    fleetSeats = PmParsing.parseFleetSeats(data),
                    fleetDegraded = PmParsing.parseFleetDegraded(data),
                    fleetNote = PmParsing.parseFleetNote(data),
                    lastUpdateMs = nowMs(),
                ),
            )
            OP_FLOW -> update(
                s.copy(
                    flowsLoaded = true,
                    flows = PmParsing.parseFlows(data),
                    lastUpdateMs = nowMs(),
                ),
            )
        }
    }

    private fun onPmEvent(ev: PmEvent) {
        update(uiState.copy(lastEventKind = ev.kind, lastUpdateMs = nowMs()))
        val kind = ev.kind ?: return
        when (kind) {
            OP_TICKETS, OP_FLEET, OP_FLOW -> requestRefresh(kind)
            else -> Unit // 'act' etc — signal only, no PM view to refresh
        }
    }

    /** Debounced re-pull: coalesce signal bursts (e.g. snapshot replays). */
    private fun requestRefresh(kind: String) {
        if (!refreshing.add(kind)) return // a refresh is already scheduled
        schedule(REFRESH_DEBOUNCE_MS) {
            refreshing.remove(kind)
            if (uiState.connection == ConnectionState.CONNECTED) pull(kind)
        }
    }

    private fun onErrorFrame(frame: ErrorFrame) {
        val human = when (frame.code) {
            "concurrent_limit" ->
                "并发上限(单 token ≤2): PC 与手机同时在线时属预期, 稍后自动重试"
            "auth" -> "token 无效: 请在设置中检查 VOICE_GATEWAY_TOKEN"
            else -> "网关错误 ${frame.code}: ${frame.message ?: frame.msg ?: ""}".trim()
        }
        update(uiState.copy(banner = human))
        if (frame.code == "pm_sub_failed" || frame.code == "pm_sub_ended") {
            schedule(RESUBSCRIBE_DELAY_MS) {
                if (uiState.connection == ConnectionState.CONNECTED) subscribe()
            }
        }
    }

    private fun safeSend(frame: ClientFrame) {
        try {
            send(frame)
        } catch (_: IllegalStateException) {
            // socket not open (reconnect window) — drop; the next CONNECTED
            // cycle re-pulls everything anyway
        }
    }

    private fun update(next: UiState) {
        uiState = next
        onChange?.invoke(next)
    }

    companion object {
        const val OP_TICKETS = "tickets"
        const val OP_FLEET = "fleet"
        const val OP_FLOW = "flow"

        /** PM 订阅口径 (spec-android §2): tickets/fleet/flow/act (act=PC 写动作只读对账). */
        val SUB_KINDS: List<String> = listOf("tickets", "fleet", "flow", "act")

        const val REFRESH_DEBOUNCE_MS = 300L
        const val RESUBSCRIBE_DELAY_MS = 1_000L
    }
}
