package dev.dshpm.proto.frame

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Event frames — gateway → client, 14 subscription kinds (spec §4).
 * Envelope on the wire: `{t:<kind>, **payload, ts}` (ts set by the bus).
 */
sealed interface EventFrame : WsFrame {
    val ts: Double?
}

/** `orch.dispatch{run_id, task_id(null), ref, credentials, lane, mode}` */
data class OrchDispatch(
    val runId: String? = null,
    val taskId: JsonElement? = null,
    val ref: String? = null,
    val credentials: JsonElement? = null,
    val lane: String? = null,
    val mode: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.dispatch"
}

/** `orch.ack` — experimental (payload NOT frozen until WSP-002); raw is authoritative. */
data class OrchAck(
    val raw: JsonObject,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.ack"
}

/** `orch.progress{dispatch_id, lines, head}` — mergeable in place (lines=max, head=latest). */
data class OrchProgress(
    val dispatchId: String? = null,
    val lines: Long? = null,
    val head: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.progress"
}

/** `orch.gate` — experimental (payload NOT frozen until WSP-002). */
data class OrchGate(
    val raw: JsonObject,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.gate"
}

/** `orch.done{ref, run_id, body | status:"cancelled"}` */
data class OrchDone(
    val ref: String? = null,
    val runId: String? = null,
    val body: String? = null,
    val status: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.done"
}

/** `orch.failed{ref, run_id, reason}` */
data class OrchFailed(
    val ref: String? = null,
    val runId: String? = null,
    val reason: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.failed"
}

/** `orch.metrics` — experimental (payload NOT frozen until WSP-002). */
data class OrchMetrics(
    val raw: JsonObject,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "orch.metrics"
}

/** `head.turn{conv_id, phase(user_start|user_end|user_text|assistant_start|assistant_end), detail?}` */
data class HeadTurn(
    val convId: String? = null,
    val phase: String? = null,
    val detail: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "head.turn"
}

/** `head.compact{conv_id, before_chars, after_chars, pinned, reason:"threshold"}` */
data class HeadCompact(
    val convId: String? = null,
    val beforeChars: Long? = null,
    val afterChars: Long? = null,
    val pinned: Boolean? = null,
    val reason: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "head.compact"
}

/** `fleet.snapshot` — whole fleet.json projection; payload shape is the file itself. */
data class FleetSnapshot(
    val payload: JsonElement,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "fleet.snapshot"
}

/** `bridge.msg{line, offset}` — incremental lines of bridge/inbox.log. */
data class BridgeMsg(
    val line: String? = null,
    val offset: Long? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "bridge.msg"
}

/** `tickets.snapshot` — full-text snapshot of tickets.md (render overwrites, never appends). */
data class TicketsSnapshot(
    val payload: JsonElement,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "tickets.snapshot"
}

/**
 * `body.push{ref, no, status, title, summary, chars, inline(≤4096|null), ts}` —
 * ledger light notification. Replay form: `{t:"body.push", items:[…(no body)], ts}`.
 */
data class BodyPush(
    val ref: String? = null,
    val no: Long? = null,
    val status: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val chars: Long? = null,
    val inline: String? = null,
    val items: List<BodyIndexRow>? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "body.push"
}

/**
 * `pm.event` — pm-host-service SSE frame passed through verbatim:
 * `kind, msgid, source, seq, path, replay` (+ PM-008 act settlement
 * `ref, tool, args, status, exitCode, ms, err`). `(source,msgid)` drives the
 * client dedup window (PM-007 semantics).
 */
data class PmEvent(
    val kind: String? = null,
    val msgid: String? = null,
    val source: String? = null,
    val seq: Long? = null,
    val path: String? = null,
    val replay: Boolean? = null,
    val ref: String? = null,
    val tool: String? = null,
    val args: JsonElement? = null,
    val status: String? = null,
    val exitCode: Long? = null,
    val ms: Double? = null,
    val err: String? = null,
    override val ts: Double? = null,
) : EventFrame {
    override val t: String get() = "pm.event"
}
