package dev.dshpm.proto.frame

import kotlinx.serialization.json.JsonElement

/**
 * Ack frames — gateway → client, 16 types (spec §3).
 */
sealed interface ServerFrame : WsFrame

/** `auth.ok{session_id, proto?("v1")}` — WSP-001 additive field, absent = pre-freeze. */
data class AuthOk(
    val sessionId: String,
    val proto: String? = null,
) : ServerFrame {
    override val t: String get() = "auth.ok"
}

/** `pong{ts(epoch float)}` */
data class Pong(val ts: Double) : ServerFrame {
    override val t: String get() = "pong"
}

/** `session.started{session_id, reseeded, entries, observe, topics}` — ack precedes topic replay. */
data class SessionStarted(
    val sessionId: String,
    val reseeded: Boolean,
    val entries: Long,
    val observe: Boolean,
    val topics: List<String>,
) : ServerFrame {
    override val t: String get() = "session.started"
}

/** `session.ended{session_id}` */
data class SessionEnded(val sessionId: String) : ServerFrame {
    override val t: String get() = "session.ended"
}

/** `gate.resolved{gate_id}` */
data class GateResolved(val gateId: String) : ServerFrame {
    override val t: String get() = "gate.resolved"
}

/** `body.item{ref, title, text, chars, ts}` */
data class BodyItem(
    val ref: String,
    val title: String,
    val text: String,
    val chars: Long,
    val ts: Double,
) : ServerFrame {
    override val t: String get() = "body.item"
}

/** Index row of `body.list_more.result` / `body.push` replay: `{no, ref, status, title, summary, chars, ts}` (no body). */
data class BodyIndexRow(
    val no: Long,
    val ref: String,
    val status: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val chars: Long? = null,
    val ts: Double? = null,
)

/** `body.list_more.result{req_id, items[], eof}` */
data class BodyListMoreResult(
    val reqId: String?,
    val items: List<BodyIndexRow>,
    val eof: Boolean,
) : ServerFrame {
    override val t: String get() = "body.list_more.result"
}

/** Result row of `fleet.cleanup`: `{id, ok}` / `{id, ok:false, error}` (+`note`, `binding-cleared`). */
data class FleetCleanupRow(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
    val note: String? = null,
    @Suppress("PropertyName") val bindingCleared: Boolean? = null, // wire: "binding-cleared"
)

/** `fleet.cleanup.result{req_id, results[], failed}` */
data class FleetCleanupResult(
    val reqId: String?,
    val results: List<FleetCleanupRow>,
    val failed: Long,
) : ServerFrame {
    override val t: String get() = "fleet.cleanup.result"
}

/**
 * Seat row of `fleet.brief.result`. NOTE: `sessionId`/`lastSeen` are camelCase
 * on the wire (frozen as-is by spec §3 — do not "fix" to snake_case).
 */
data class FleetSeat(
    val code: String,
    val sessionId: String? = null,
    val live: Boolean? = null,
    val running: Boolean? = null,
    val title: String? = null,
    val task: String? = null,
    val idleS: Double? = null,        // wire: idle_s
    val lastSeen: Double? = null,     // wire: last_seen
)

/** `liaison` block: `{bound, code, sessionId, archived}` (camelCase frozen). */
data class FleetLiaison(
    val bound: Boolean,
    val code: String? = null,
    val sessionId: String? = null,
    val archived: Boolean? = null,
)

/** `fleet.brief.result{req_id, seats[], inactive, liaison{...}, note?}` */
data class FleetBriefResult(
    val reqId: String?,
    val seats: List<FleetSeat>,
    val inactive: Long,
    val liaison: FleetLiaison,
    val note: String? = null,
) : ServerFrame {
    override val t: String get() = "fleet.brief.result"
}

/** `liaison.result{req_id, op("bind"|"unbind"), ok, was_bound?|liaison?, error?}` */
data class LiaisonResult(
    val reqId: String?,
    val op: String,
    val ok: Boolean,
    val wasBound: Boolean? = null,
    val liaison: FleetLiaison? = null,
    val error: String? = null,
) : ServerFrame {
    override val t: String get() = "liaison.result"
}

/** `run.cancel.result{req_id, ref, ok, state?("cancelled")|error?}` */
data class RunCancelResult(
    val reqId: String?,
    val ref: String,
    val ok: Boolean,
    val state: String? = null,
    val error: String? = null,
) : ServerFrame {
    override val t: String get() = "run.cancel.result"
}

/** `whiteboard.set.result{req_id, ok, chars?|reason?}` */
data class WhiteboardSetResult(
    val reqId: String?,
    val ok: Boolean,
    val chars: Long? = null,
    val reason: String? = null,
) : ServerFrame {
    override val t: String get() = "whiteboard.set.result"
}

/** `head.list.result{req_id, active, file_backed, env_pinned, profiles[]}` — profiles shape not detailed in v1. */
data class HeadListResult(
    val reqId: String?,
    val active: String,
    val fileBacked: Boolean,
    val envPinned: Boolean,
    val profiles: List<JsonElement>,
) : ServerFrame {
    override val t: String get() = "head.list.result"
}

/** `head.switch.result{req_id, ok, active, note?}` */
data class HeadSwitchResult(
    val reqId: String?,
    val ok: Boolean,
    val active: String,
    val note: String? = null,
) : ServerFrame {
    override val t: String get() = "head.switch.result"
}

/** pm.res error block: `{code, message}`. */
data class PmError(
    val code: String,
    val message: String? = null,
)

/** `pm.res{id, data?|error?}` — GW-001 envelope; errors never close the connection. */
data class PmRes(
    val id: JsonElement,
    val data: JsonElement? = null,
    val error: PmError? = null,
) : ServerFrame {
    override val t: String get() = "pm.res"
}

/**
 * `error` frame — dual-field precedent frozen as-is (§0): legacy `code`/`msg`
 * and new `type:"error"`/`message` are BOTH always present, plus optional `req_id`.
 */
data class ErrorFrame(
    val code: String,
    val msg: String? = null,
    val message: String? = null,
    val reqId: String? = null,
) : ServerFrame {
    override val t: String get() = "error"
    val type: String get() = "error"
}
