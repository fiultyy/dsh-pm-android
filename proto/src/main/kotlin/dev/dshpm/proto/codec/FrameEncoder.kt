package dev.dshpm.proto.codec

import dev.dshpm.proto.frame.Auth
import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.BodyGet
import dev.dshpm.proto.frame.BodyIndexRow
import dev.dshpm.proto.frame.BodyItem
import dev.dshpm.proto.frame.BodyListMore
import dev.dshpm.proto.frame.BodyListMoreResult
import dev.dshpm.proto.frame.BodyPush
import dev.dshpm.proto.frame.BridgeMsg
import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.EventFrame
import dev.dshpm.proto.frame.FleetBrief
import dev.dshpm.proto.frame.FleetBriefResult
import dev.dshpm.proto.frame.FleetCleanup
import dev.dshpm.proto.frame.FleetCleanupResult
import dev.dshpm.proto.frame.FleetCleanupRow
import dev.dshpm.proto.frame.FleetLiaison
import dev.dshpm.proto.frame.FleetSeat
import dev.dshpm.proto.frame.FleetSnapshot
import dev.dshpm.proto.frame.GateResolve
import dev.dshpm.proto.frame.GateResolved
import dev.dshpm.proto.frame.HeadCompact
import dev.dshpm.proto.frame.HeadList
import dev.dshpm.proto.frame.HeadListResult
import dev.dshpm.proto.frame.HeadSwitch
import dev.dshpm.proto.frame.HeadSwitchResult
import dev.dshpm.proto.frame.HeadTurn
import dev.dshpm.proto.frame.LiaisonBind
import dev.dshpm.proto.frame.LiaisonResult
import dev.dshpm.proto.frame.LiaisonUnbind
import dev.dshpm.proto.frame.MalformedFrame
import dev.dshpm.proto.frame.OrchAck
import dev.dshpm.proto.frame.OrchDispatch
import dev.dshpm.proto.frame.OrchDone
import dev.dshpm.proto.frame.OrchFailed
import dev.dshpm.proto.frame.OrchGate
import dev.dshpm.proto.frame.OrchMetrics
import dev.dshpm.proto.frame.OrchProgress
import dev.dshpm.proto.frame.Ping
import dev.dshpm.proto.frame.PmError
import dev.dshpm.proto.frame.PmEvent
import dev.dshpm.proto.frame.PmReq
import dev.dshpm.proto.frame.PmRes
import dev.dshpm.proto.frame.PmSub
import dev.dshpm.proto.frame.PmUnsub
import dev.dshpm.proto.frame.Pong
import dev.dshpm.proto.frame.RunCancel
import dev.dshpm.proto.frame.RunCancelResult
import dev.dshpm.proto.frame.ServerFrame
import dev.dshpm.proto.frame.SessionEnd
import dev.dshpm.proto.frame.SessionEnded
import dev.dshpm.proto.frame.SessionStart
import dev.dshpm.proto.frame.SessionStarted
import dev.dshpm.proto.frame.TicketsSnapshot
import dev.dshpm.proto.frame.UnknownFrame
import dev.dshpm.proto.frame.WhiteboardSet
import dev.dshpm.proto.frame.WhiteboardSetResult
import dev.dshpm.proto.frame.WsFrame
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Encodes typed frames to wire JSON (field names frozen by spec-ws-protocol-v1.md). */
internal object FrameEncoder {

    fun encode(frame: WsFrame): String = when (frame) {
        is UnknownFrame -> frame.raw?.toString() ?: """{"t":"${frame.t}"}"""
        is MalformedFrame -> frame.text
        is ClientFrame -> encodeClient(frame)
        is ServerFrame -> encodeServer(frame)
        is EventFrame -> encodeEvent(frame)
    }.toString()

    private fun encodeClient(f: ClientFrame): JsonObject = when (f) {
        is Auth -> obj(f.t) { put("token", f.token) }
        is SessionStart -> obj(f.t) {
            putOpt("observe", f.observe)
            putOpt("topics", f.topics)
            putOpt("session_id", f.sessionId)
        }
        is SessionEnd -> obj(f.t)
        is Ping -> obj(f.t)
        is GateResolve -> obj(f.t) {
            putOpt("req_id", f.reqId)
            put("gate_id", f.gateId)
            put("resolution", f.resolution)
        }
        is BodyGet -> obj(f.t) { putOpt("req_id", f.reqId); put("ref", f.ref) }
        is BodyListMore -> obj(f.t) {
            putOpt("req_id", f.reqId)
            put("before_ts", f.beforeTs)
            putOpt("limit", f.limit?.toLong())
        }
        is FleetCleanup -> obj(f.t) {
            putOpt("req_id", f.reqId)
            putJsonArray("ids") { f.ids.forEach { add(it) } }
            put("mode", f.mode.wire)
        }
        is FleetBrief -> obj(f.t) { putOpt("req_id", f.reqId) }
        is LiaisonUnbind -> obj(f.t) { putOpt("req_id", f.reqId) }
        is LiaisonBind -> obj(f.t) { putOpt("req_id", f.reqId); put("code", f.code) }
        is RunCancel -> obj(f.t) { putOpt("req_id", f.reqId); put("ref", f.ref) }
        is WhiteboardSet -> obj(f.t) { putOpt("req_id", f.reqId); put("text", f.text) }
        is HeadList -> obj(f.t) { putOpt("req_id", f.reqId) }
        is HeadSwitch -> obj(f.t) { putOpt("req_id", f.reqId); put("name", f.name) }
        is PmReq -> obj(f.t) {
            put("id", f.id)
            put("op", f.op)
            putOptElement("params", f.params)
        }
        is PmSub -> obj(f.t) { put("id", f.id); putJsonArray("kinds") { f.kinds.forEach { add(it) } } }
        is PmUnsub -> obj(f.t) { put("id", f.id) }
    }

    private fun encodeServer(f: ServerFrame): JsonObject = when (f) {
        // auth.ok: field order t → session_id → proto (spec §3).
        is AuthOk -> obj(f.t) {
            put("session_id", f.sessionId)
            putOpt("proto", f.proto)
        }
        is Pong -> obj(f.t) { put("ts", f.ts) }
        is SessionStarted -> obj(f.t) {
            put("session_id", f.sessionId)
            put("reseeded", f.reseeded)
            put("entries", f.entries)
            put("observe", f.observe)
            putJsonArray("topics") { f.topics.forEach { add(it) } }
        }
        is SessionEnded -> obj(f.t) { put("session_id", f.sessionId) }
        is GateResolved -> obj(f.t) { put("gate_id", f.gateId) }
        is BodyItem -> obj(f.t) {
            put("ref", f.ref); put("title", f.title); put("text", f.text)
            put("chars", f.chars); put("ts", f.ts)
        }
        is BodyListMoreResult -> obj(f.t) {
            putOpt("req_id", f.reqId)
            putJsonArray("items") { f.items.forEach { add(encodeBodyRow(it)) } }
            put("eof", f.eof)
        }
        is FleetCleanupResult -> obj(f.t) {
            putOpt("req_id", f.reqId)
            putJsonArray("results") { f.results.forEach { add(encodeCleanupRow(it)) } }
            put("failed", f.failed)
        }
        is FleetBriefResult -> obj(f.t) {
            putOpt("req_id", f.reqId)
            putJsonArray("seats") { f.seats.forEach { add(encodeSeat(it)) } }
            put("inactive", f.inactive)
            put("liaison", encodeLiaison(f.liaison))
            putOpt("note", f.note)
        }
        is LiaisonResult -> obj(f.t) {
            putOpt("req_id", f.reqId)
            put("op", f.op)
            put("ok", f.ok)
            putOpt("was_bound", f.wasBound)
            putOptElement("liaison", f.liaison?.let { encodeLiaison(it) })
            putOpt("error", f.error)
        }
        is RunCancelResult -> obj(f.t) {
            putOpt("req_id", f.reqId); put("ref", f.ref); put("ok", f.ok)
            putOpt("state", f.state); putOpt("error", f.error)
        }
        is WhiteboardSetResult -> obj(f.t) {
            putOpt("req_id", f.reqId); put("ok", f.ok)
            putOpt("chars", f.chars); putOpt("reason", f.reason)
        }
        is HeadListResult -> obj(f.t) {
            putOpt("req_id", f.reqId)
            put("active", f.active)
            put("file_backed", f.fileBacked)
            put("env_pinned", f.envPinned)
            putJsonArray("profiles") { f.profiles.forEach { add(it) } }
        }
        is HeadSwitchResult -> obj(f.t) {
            putOpt("req_id", f.reqId); put("ok", f.ok); put("active", f.active); putOpt("note", f.note)
        }
        is PmRes -> obj(f.t) {
            put("id", f.id)
            putOptElement("data", f.data)
            putOptElement("error", f.error?.let { encodePmError(it) })
        }
        // error: dual-field precedent frozen — code/msg AND type/message both always present.
        is ErrorFrame -> obj(f.t) {
            put("code", f.code)
            putOpt("msg", f.msg)
            put("type", "error")
            putOpt("message", f.message)
            putOpt("req_id", f.reqId)
        }
    }

    private fun encodeEvent(f: EventFrame): JsonObject = when (f) {
        is OrchDispatch -> obj(f.t) {
            put("run_id", f.runId)
            putElementOrNull("task_id", f.taskId)
            put("ref", f.ref)
            putElementOrNull("credentials", f.credentials)
            put("lane", f.lane)
            put("mode", f.mode)
            putOpt("ts", f.ts)
        }
        is OrchAck -> obj(f.t) { f.raw.forEach { (k, v) -> put(k, v) }; putOpt("ts", f.ts) }
        is OrchProgress -> obj(f.t) {
            putOpt("dispatch_id", f.dispatchId); putOpt("lines", f.lines)
            putOpt("head", f.head); putOpt("ts", f.ts)
        }
        is OrchGate -> obj(f.t) { f.raw.forEach { (k, v) -> put(k, v) }; putOpt("ts", f.ts) }
        is OrchDone -> obj(f.t) {
            putOpt("ref", f.ref); putOpt("run_id", f.runId)
            putOpt("body", f.body); putOpt("status", f.status); putOpt("ts", f.ts)
        }
        is OrchFailed -> obj(f.t) {
            putOpt("ref", f.ref); putOpt("run_id", f.runId); putOpt("reason", f.reason); putOpt("ts", f.ts)
        }
        is OrchMetrics -> obj(f.t) { f.raw.forEach { (k, v) -> put(k, v) }; putOpt("ts", f.ts) }
        is HeadTurn -> obj(f.t) {
            putOpt("conv_id", f.convId); putOpt("phase", f.phase); putOpt("detail", f.detail); putOpt("ts", f.ts)
        }
        is HeadCompact -> obj(f.t) {
            putOpt("conv_id", f.convId); putOpt("before_chars", f.beforeChars)
            putOpt("after_chars", f.afterChars); putOpt("pinned", f.pinned)
            putOpt("reason", f.reason); putOpt("ts", f.ts)
        }
        is FleetSnapshot -> obj(f.t) { put("snapshot", f.payload); putOpt("ts", f.ts) }
        is BridgeMsg -> obj(f.t) { putOpt("line", f.line); putOpt("offset", f.offset); putOpt("ts", f.ts) }
        is TicketsSnapshot -> obj(f.t) { put("snapshot", f.payload); putOpt("ts", f.ts) }
        is BodyPush -> obj(f.t) {
            putOpt("ref", f.ref); putOpt("no", f.no); putOpt("status", f.status)
            putOpt("title", f.title); putOpt("summary", f.summary); putOpt("chars", f.chars)
            putOpt("inline", f.inline)
            if (f.items != null) putJsonArray("items") { f.items.forEach { add(encodeBodyRow(it)) } }
            putOpt("ts", f.ts)
        }
        is PmEvent -> obj(f.t) {
            putOpt("kind", f.kind); putOpt("msgid", f.msgid); putOpt("source", f.source)
            putOpt("seq", f.seq); putOpt("path", f.path); putOpt("replay", f.replay)
            putOpt("ref", f.ref); putOpt("tool", f.tool)
            putOptElement("args", f.args)
            putOpt("status", f.status); putOpt("exitCode", f.exitCode)
            putOpt("ms", f.ms); putOpt("err", f.err); putOpt("ts", f.ts)
        }
    }

    private fun obj(t: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("t", t); block() }

    private fun encodeBodyRow(r: BodyIndexRow): JsonObject = buildJsonObject {
        put("no", r.no); put("ref", r.ref)
        putOpt("status", r.status); putOpt("title", r.title)
        putOpt("summary", r.summary); putOpt("chars", r.chars); putOpt("ts", r.ts)
    }

    private fun encodeCleanupRow(r: FleetCleanupRow): JsonObject = buildJsonObject {
        put("id", r.id); put("ok", r.ok)
        putOpt("error", r.error); putOpt("note", r.note)
        putOpt("binding-cleared", r.bindingCleared)
    }

    private fun encodeSeat(s: FleetSeat): JsonObject = buildJsonObject {
        put("code", s.code)
        // camelCase wire names frozen by spec §3 — intentional, do not snake_case.
        putOpt("sessionId", s.sessionId)
        putOpt("live", s.live); putOpt("running", s.running); putOpt("title", s.title)
        putOpt("task", s.task); putOpt("idle_s", s.idleS); putOpt("last_seen", s.lastSeen)
    }

    private fun encodeLiaison(l: FleetLiaison): JsonObject = buildJsonObject {
        put("bound", l.bound)
        putOpt("code", l.code); putOpt("sessionId", l.sessionId); putOpt("archived", l.archived)
    }

    private fun encodePmError(e: PmError): JsonObject = buildJsonObject {
        put("code", e.code); putOpt("message", e.message)
    }
}


