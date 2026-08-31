package dev.dshpm.proto.frame

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Inbound frames — client → gateway, 18 types (spec §2).
 * Kotlin names are camelCase; wire names are frozen snake_case (codec layer).
 */
sealed interface ClientFrame : WsFrame

/** `auth{token!}` — token = VOICE_GATEWAY_TOKEN; duplicate auth is rejected. */
data class Auth(val token: String) : ClientFrame {
    override val t: String get() = "auth"
}

/** `session.start{observe?, topics?, session_id?}` — session_id resumes a dropped conv. */
data class SessionStart(
    val observe: Boolean? = null,
    val topics: List<String>? = null,
    val sessionId: String? = null,
) : ClientFrame {
    override val t: String get() = "session.start"
}

/** `session.end` — graceful end, drops the resume slot. */
object SessionEnd : ClientFrame {
    override val t: String get() = "session.end"
}

/** `ping` — app-level heartbeat, answered by `pong{ts}`. */
object Ping : ClientFrame {
    override val t: String get() = "ping"
}

/** `gate.resolve{req_id?, gate_id!, resolution!}` — resolution shape not typed in v1. */
data class GateResolve(
    val reqId: String? = null,
    val gateId: String,
    val resolution: JsonElement,
) : ClientFrame {
    override val t: String get() = "gate.resolve"
}

/** `body.get{req_id?, ref!}` */
data class BodyGet(
    val reqId: String? = null,
    val ref: String,
) : ClientFrame {
    override val t: String get() = "body.get"
}

/** `body.list_more{req_id?, before_ts!(number), limit?(1..200, default 50)}` */
data class BodyListMore(
    val reqId: String? = null,
    val beforeTs: Double,
    val limit: Int? = null,
) : ClientFrame {
    override val t: String get() = "body.list_more"
}

/** `fleet.cleanup{req_id?, ids!(non-empty), mode!("release"|"end")}` */
data class FleetCleanup(
    val reqId: String? = null,
    val ids: List<String>,
    val mode: FleetCleanupMode,
) : ClientFrame {
    override val t: String get() = "fleet.cleanup"
}

enum class FleetCleanupMode(val wire: String) {
    RELEASE("release"),
    END("end");

    companion object {
        fun fromWire(wire: String): FleetCleanupMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** `fleet.brief{req_id?}` */
data class FleetBrief(val reqId: String? = null) : ClientFrame {
    override val t: String get() = "fleet.brief"
}

/** `liaison.unbind{req_id?}` — unbind when not bound is an idempotent no-op. */
data class LiaisonUnbind(val reqId: String? = null) : ClientFrame {
    override val t: String get() = "liaison.unbind"
}

/** `liaison.bind{req_id?, code!}` */
data class LiaisonBind(
    val reqId: String? = null,
    val code: String,
) : ClientFrame {
    override val t: String get() = "liaison.bind"
}

/** `run.cancel{req_id?, ref!}` — duplicate cancel of the same ref stays ok:true. */
data class RunCancel(
    val reqId: String? = null,
    val ref: String,
) : ClientFrame {
    override val t: String get() = "run.cancel"
}

/** `whiteboard.set{req_id?, text!}` — whole-board overwrite, terminal consistency. */
data class WhiteboardSet(
    val reqId: String? = null,
    val text: String,
) : ClientFrame {
    override val t: String get() = "whiteboard.set"
}

/** `head.list{req_id?}` */
data class HeadList(val reqId: String? = null) : ClientFrame {
    override val t: String get() = "head.list"
}

/** `head.switch{req_id?, name!}` */
data class HeadSwitch(
    val reqId: String? = null,
    val name: String,
) : ClientFrame {
    override val t: String get() = "head.switch"
}

/**
 * `pm.req{id!(string|int non-empty), op!(⊆[A-Za-z0-9_-]{1,64}), params?(object)}`.
 * id is string|int on the wire → [JsonPrimitive] keeps the exact shape.
 */
data class PmReq(
    val id: JsonPrimitive,
    val op: String,
    val params: JsonObject? = null,
) : ClientFrame {
    override val t: String get() = "pm.req"
}

/** `pm.sub{id!, kinds!(≤32 items, each ⊆[A-Za-z0-9_.-]{1,64})}` */
data class PmSub(
    val id: JsonPrimitive,
    val kinds: List<String>,
) : ClientFrame {
    override val t: String get() = "pm.sub"
}

/** `pm.unsub{id!}` — unsubscribing nothing is an idempotent no-op. */
data class PmUnsub(val id: JsonPrimitive) : ClientFrame {
    override val t: String get() = "pm.unsub"
}
