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
import dev.dshpm.proto.frame.FleetCleanupMode
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Decodes wire text into typed frames.
 *
 * Tolerance (spec §0): unknown `t` → [UnknownFrame]; unknown fields ignored;
 * malformed JSON / non-object / missing `t` → [MalformedFrame]. Never throws.
 */
internal object FrameDecoder {

    fun decode(text: String): WsFrame {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return MalformedFrame(text, e.message ?: "parse error")
        }
        val obj = element as? JsonObject ?: return MalformedFrame(text, "not a JSON object")
        val tPrim = obj["t"] as? kotlinx.serialization.json.JsonPrimitive
        val t = if (tPrim != null && tPrim.isString) tPrim.content else null
            ?: return MalformedFrame(text, "missing or non-string 't'")
        return when (t) {
            // ---- client → gateway (18) ----
            "auth" -> Auth(token = obj.optString("token") ?: "")
            "session.start" -> SessionStart(
                observe = obj.optBoolean("observe"),
                topics = obj.optStringList("topics"),
                sessionId = obj.optString("session_id"),
            )
            "session.end" -> SessionEnd
            "ping" -> Ping
            "gate.resolve" -> GateResolve(
                reqId = obj.optString("req_id"),
                gateId = obj.optString("gate_id") ?: "",
                resolution = obj.optElement("resolution") ?: kotlinx.serialization.json.JsonNull,
            )
            "body.get" -> BodyGet(reqId = obj.optString("req_id"), ref = obj.optString("ref") ?: "")
            "body.list_more" -> BodyListMore(
                reqId = obj.optString("req_id"),
                beforeTs = obj.optDouble("before_ts") ?: 0.0,
                limit = obj.optLong("limit")?.toInt(),
            )
            "fleet.cleanup" -> FleetCleanup(
                reqId = obj.optString("req_id"),
                ids = obj.optStringList("ids").orEmpty(),
                mode = FleetCleanupMode.fromWire(obj.optString("mode") ?: "")
                    ?: FleetCleanupMode.RELEASE,
            )
            "fleet.brief" -> FleetBrief(reqId = obj.optString("req_id"))
            "liaison.unbind" -> LiaisonUnbind(reqId = obj.optString("req_id"))
            "liaison.bind" -> LiaisonBind(reqId = obj.optString("req_id"), code = obj.optString("code") ?: "")
            "run.cancel" -> RunCancel(reqId = obj.optString("req_id"), ref = obj.optString("ref") ?: "")
            "whiteboard.set" -> WhiteboardSet(reqId = obj.optString("req_id"), text = obj.optString("text") ?: "")
            "head.list" -> HeadList(reqId = obj.optString("req_id"))
            "head.switch" -> HeadSwitch(reqId = obj.optString("req_id"), name = obj.optString("name") ?: "")
            "pm.req" -> PmReq(
                id = obj.optPrimitive("id") ?: kotlinx.serialization.json.JsonNull,
                op = obj.optString("op") ?: "",
                params = obj.optObject("params"),
            )
            "pm.sub" -> PmSub(
                id = obj.optPrimitive("id") ?: kotlinx.serialization.json.JsonNull,
                kinds = obj.optStringList("kinds").orEmpty(),
            )
            "pm.unsub" -> PmUnsub(id = obj.optPrimitive("id") ?: kotlinx.serialization.json.JsonNull)

            // ---- gateway → client · ack (16) ----
            "auth.ok" -> AuthOk(sessionId = obj.optString("session_id") ?: "", proto = obj.optString("proto"))
            "pong" -> Pong(ts = obj.optDouble("ts") ?: 0.0)
            "session.started" -> SessionStarted(
                sessionId = obj.optString("session_id") ?: "",
                reseeded = obj.optBoolean("reseeded") ?: false,
                entries = obj.optLong("entries") ?: 0L,
                observe = obj.optBoolean("observe") ?: false,
                topics = obj.optStringList("topics").orEmpty(),
            )
            "session.ended" -> SessionEnded(sessionId = obj.optString("session_id") ?: "")
            "gate.resolved" -> GateResolved(gateId = obj.optString("gate_id") ?: "")
            "body.item" -> BodyItem(
                ref = obj.optString("ref") ?: "",
                title = obj.optString("title") ?: "",
                text = obj.optString("text") ?: "",
                chars = obj.optLong("chars") ?: 0L,
                ts = obj.optDouble("ts") ?: 0.0,
            )
            "body.list_more.result" -> BodyListMoreResult(
                reqId = obj.optString("req_id"),
                items = obj.optArray("items")?.map { decodeBodyRow(it) }.orEmpty(),
                eof = obj.optBoolean("eof") ?: false,
            )
            "fleet.cleanup.result" -> FleetCleanupResult(
                reqId = obj.optString("req_id"),
                results = obj.optArray("results")?.mapNotNull { it as? JsonObject }
                    ?.map { decodeCleanupRow(it) }.orEmpty(),
                failed = obj.optLong("failed") ?: 0L,
            )
            "fleet.brief.result" -> FleetBriefResult(
                reqId = obj.optString("req_id"),
                seats = obj.optArray("seats")?.mapNotNull { it as? JsonObject }
                    ?.map { decodeSeat(it) }.orEmpty(),
                inactive = obj.optLong("inactive") ?: 0L,
                liaison = obj.optObject("liaison")?.let { decodeLiaison(it) }
                    ?: FleetLiaison(bound = false),
                note = obj.optString("note"),
            )
            "liaison.result" -> LiaisonResult(
                reqId = obj.optString("req_id"),
                op = obj.optString("op") ?: "",
                ok = obj.optBoolean("ok") ?: false,
                wasBound = obj.optBoolean("was_bound"),
                liaison = obj.optObject("liaison")?.let { decodeLiaison(it) },
                error = obj.optString("error"),
            )
            "run.cancel.result" -> RunCancelResult(
                reqId = obj.optString("req_id"),
                ref = obj.optString("ref") ?: "",
                ok = obj.optBoolean("ok") ?: false,
                state = obj.optString("state"),
                error = obj.optString("error"),
            )
            "whiteboard.set.result" -> WhiteboardSetResult(
                reqId = obj.optString("req_id"),
                ok = obj.optBoolean("ok") ?: false,
                chars = obj.optLong("chars"),
                reason = obj.optString("reason"),
            )
            "head.list.result" -> HeadListResult(
                reqId = obj.optString("req_id"),
                active = obj.optString("active") ?: "",
                fileBacked = obj.optBoolean("file_backed") ?: false,
                envPinned = obj.optBoolean("env_pinned") ?: false,
                profiles = obj.optArray("profiles")?.toList().orEmpty(),
            )
            "head.switch.result" -> HeadSwitchResult(
                reqId = obj.optString("req_id"),
                ok = obj.optBoolean("ok") ?: false,
                active = obj.optString("active") ?: "",
                note = obj.optString("note"),
            )
            "pm.res" -> PmRes(
                id = obj.optElement("id") ?: kotlinx.serialization.json.JsonNull,
                data = obj.optElement("data"),
                error = obj.optObject("error")?.let {
                    PmError(code = it.optString("code") ?: "", message = it.optString("message"))
                },
            )
            "error" -> ErrorFrame(
                code = obj.optString("code") ?: "",
                msg = obj.optString("msg"),
                message = obj.optString("message"),
                reqId = obj.optString("req_id"),
            )

            // ---- gateway → client · events (14) ----
            "orch.dispatch" -> OrchDispatch(
                runId = obj.optString("run_id"),
                taskId = obj.optElement("task_id"),
                ref = obj.optString("ref"),
                credentials = obj.optElement("credentials"),
                lane = obj.optString("lane"),
                mode = obj.optString("mode"),
                ts = obj.optDouble("ts"),
            )
            "orch.ack" -> OrchAck(raw = payloadOf(obj), ts = obj.optDouble("ts"))
            "orch.progress" -> OrchProgress(
                dispatchId = obj.optString("dispatch_id"),
                lines = obj.optLong("lines"),
                head = obj.optString("head"),
                ts = obj.optDouble("ts"),
            )
            "orch.gate" -> OrchGate(raw = payloadOf(obj), ts = obj.optDouble("ts"))
            "orch.done" -> OrchDone(
                ref = obj.optString("ref"),
                runId = obj.optString("run_id"),
                body = obj.optString("body"),
                status = obj.optString("status"),
                ts = obj.optDouble("ts"),
            )
            "orch.failed" -> OrchFailed(
                ref = obj.optString("ref"),
                runId = obj.optString("run_id"),
                reason = obj.optString("reason"),
                ts = obj.optDouble("ts"),
            )
            "orch.metrics" -> OrchMetrics(raw = payloadOf(obj), ts = obj.optDouble("ts"))
            "head.turn" -> HeadTurn(
                convId = obj.optString("conv_id"),
                phase = obj.optString("phase"),
                detail = obj.optString("detail"),
                ts = obj.optDouble("ts"),
            )
            "head.compact" -> HeadCompact(
                convId = obj.optString("conv_id"),
                beforeChars = obj.optLong("before_chars"),
                afterChars = obj.optLong("after_chars"),
                pinned = obj.optBoolean("pinned"),
                reason = obj.optString("reason"),
                ts = obj.optDouble("ts"),
            )
            "fleet.snapshot" -> FleetSnapshot(
                payload = obj.optElement("snapshot") ?: obj,
                ts = obj.optDouble("ts"),
            )
            "bridge.msg" -> BridgeMsg(line = obj.optString("line"), offset = obj.optLong("offset"), ts = obj.optDouble("ts"))
            "tickets.snapshot" -> TicketsSnapshot(
                payload = obj.optElement("snapshot") ?: obj,
                ts = obj.optDouble("ts"),
            )
            "body.push" -> decodeBodyPush(obj)
            "pm.event" -> PmEvent(
                kind = obj.optString("kind"),
                msgid = obj.optString("msgid"),
                source = obj.optString("source"),
                seq = obj.optLong("seq"),
                path = obj.optString("path"),
                replay = obj.optBoolean("replay"),
                ref = obj.optString("ref"),
                tool = obj.optString("tool"),
                args = obj.optElement("args"),
                status = obj.optString("status"),
                exitCode = obj.optLong("exitCode"),
                ms = obj.optDouble("ms"),
                err = obj.optString("err"),
                ts = obj.optDouble("ts"),
            )

            // ---- frozen "only-add": unknown types are tolerated ----
            else -> UnknownFrame(t, obj)
        }
    }

    /** Payload view of an experimental event envelope: everything except `t` and `ts`. */
    private fun payloadOf(obj: JsonObject): JsonObject {
        val m = obj.toMutableMap()
        m.remove("t")
        m.remove("ts")
        return JsonObject(m)
    }

    private fun decodeBodyPush(obj: JsonObject): BodyPush {
        val items = obj.optArray("items")
        return if (items != null) {
            BodyPush(items = items.map { decodeBodyRow(it) }, ts = obj.optDouble("ts"))
        } else {
            BodyPush(
                ref = obj.optString("ref"),
                no = obj.optLong("no"),
                status = obj.optString("status"),
                title = obj.optString("title"),
                summary = obj.optString("summary"),
                chars = obj.optLong("chars"),
                inline = obj.optString("inline"),
                ts = obj.optDouble("ts"),
            )
        }
    }

    private fun decodeBodyRow(el: kotlinx.serialization.json.JsonElement): BodyIndexRow {
        val o = el as? JsonObject ?: return BodyIndexRow(no = 0L, ref = "")
        return BodyIndexRow(
            no = o.optLong("no") ?: 0L,
            ref = o.optString("ref") ?: "",
            status = o.optString("status"),
            title = o.optString("title"),
            summary = o.optString("summary"),
            chars = o.optLong("chars"),
            ts = o.optDouble("ts"),
        )
    }

    private fun decodeCleanupRow(o: JsonObject): FleetCleanupRow = FleetCleanupRow(
        id = o.optString("id") ?: "",
        ok = o.optBoolean("ok") ?: false,
        error = o.optString("error"),
        note = o.optString("note"),
        bindingCleared = o.optBoolean("binding-cleared"),
    )

    private fun decodeSeat(o: JsonObject): FleetSeat = FleetSeat(
        code = o.optString("code") ?: "",
        sessionId = o.optString("sessionId"),
        live = o.optBoolean("live"),
        running = o.optBoolean("running"),
        title = o.optString("title"),
        task = o.optString("task"),
        idleS = o.optDouble("idle_s"),
        lastSeen = o.optDouble("last_seen"),
    )

    private fun decodeLiaison(o: JsonObject): FleetLiaison = FleetLiaison(
        bound = o.optBoolean("bound") ?: false,
        code = o.optString("code"),
        sessionId = o.optString("sessionId"),
        archived = o.optBoolean("archived"),
    )
}
