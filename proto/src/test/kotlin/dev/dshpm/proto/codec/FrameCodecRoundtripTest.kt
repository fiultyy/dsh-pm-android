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
import dev.dshpm.proto.frame.WhiteboardSet
import dev.dshpm.proto.frame.WhiteboardSetResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** Roundtrip proof over the full frozen v1 frame set: 18 client + 16 ack + 14 events. */
class FrameCodecRoundtripTest {

    private fun roundtrip(frame: dev.dshpm.proto.frame.WsFrame) {
        val wire = FrameCodec.encode(frame)
        val back = FrameCodec.decode(wire)
        assertEquals("roundtrip mismatch for ${frame.t}: $wire", frame, back)
    }

    private val clientFrames: List<ClientFrame> = listOf(
        Auth(token = "tok-abc123"),
        SessionStart(observe = true, topics = listOf("head.turn", "body.push"), sessionId = "conv-42"),
        SessionEnd,
        Ping,
        GateResolve(reqId = "r1", gateId = "g-1", resolution = JsonPrimitive("approve")),
        BodyGet(reqId = "r2", ref = "vh-1a2b3c4d"),
        BodyListMore(reqId = "r3", beforeTs = 1788135000.5, limit = 50),
        BodyListMore(beforeTs = 1788135000.0), // req_id/limit omitted
        FleetCleanup(reqId = "r4", ids = listOf("term-1", "term-2"), mode = FleetCleanupMode.END),
        FleetBrief(reqId = "r5"),
        LiaisonUnbind(reqId = "r6"),
        LiaisonBind(reqId = "r7", code = "code-1"),
        RunCancel(reqId = "r8", ref = "vh-9f8e7d6c"),
        WhiteboardSet(reqId = "r9", text = "board text"),
        HeadList(reqId = "r10"),
        HeadSwitch(reqId = "r11", name = "omp"),
        PmReq(id = JsonPrimitive(42), op = "ledger.bump", params = buildJsonObject { put("k", "v") }),
        PmReq(id = JsonPrimitive("op-1"), op = "flow.run"), // string id, params omitted
        PmSub(id = JsonPrimitive("sub-1"), kinds = listOf("tickets", "fleet")),
        PmUnsub(id = JsonPrimitive("sub-2")),
    )

    private val serverFrames: List<ServerFrame> = listOf(
        AuthOk(sessionId = "s-1a2b3c4d", proto = "v1"),
        AuthOk(sessionId = "s-00000000"), // pre-freeze: proto absent
        Pong(ts = 1788135286.5),
        SessionStarted(sessionId = "conv-42", reseeded = true, entries = 7, observe = false, topics = listOf("head.turn")),
        SessionEnded(sessionId = "conv-42"),
        GateResolved(gateId = "g-1"),
        BodyItem(ref = "vh-1a2b3c4d", title = "T", text = "body text", chars = 120, ts = 1788135286.5),
        BodyListMoreResult(
            reqId = "r3",
            items = listOf(BodyIndexRow(no = 1, ref = "vh-1a2b3c4d", status = "done", title = "T", summary = "s", chars = 120, ts = 1788135286.5)),
            eof = true,
        ),
        FleetCleanupResult(
            reqId = "r4",
            results = listOf(
                FleetCleanupRow(id = "term-1", ok = true),
                FleetCleanupRow(id = "term-2", ok = false, error = "end-failed", note = "active-liaison", bindingCleared = true),
            ),
            failed = 1,
        ),
        FleetBriefResult(
            reqId = "r5",
            seats = listOf(FleetSeat(code = "code-1", sessionId = "sess-1", live = true, running = false, title = "t", task = "tk", idleS = 12.5, lastSeen = 1788135286.0)),
            inactive = 2,
            liaison = FleetLiaison(bound = true, code = "code-1", sessionId = "sess-1", archived = false),
            note = "n",
        ),
        LiaisonResult(reqId = "r7", op = "bind", ok = true, wasBound = false, liaison = FleetLiaison(bound = true, code = "code-1", sessionId = "sess-1", archived = null)),
        LiaisonResult(reqId = "r6", op = "unbind", ok = false, error = "target session archived"),
        RunCancelResult(reqId = "r8", ref = "vh-9f8e7d6c", ok = true, state = "cancelled"),
        WhiteboardSetResult(reqId = "r9", ok = true, chars = 120),
        WhiteboardSetResult(reqId = "r9b", ok = false, reason = "limit"),
        HeadListResult(reqId = "r10", active = "omp", fileBacked = true, envPinned = false, profiles = listOf(JsonPrimitive("omp"), JsonPrimitive("cc"))),
        HeadSwitchResult(reqId = "r11", ok = true, active = "cc", note = "下一次语音连接生效"),
        PmRes(id = JsonPrimitive(42), data = buildJsonObject { put("ok", true) }),
        PmRes(id = JsonPrimitive("op-1"), error = PmError(code = "pm_timeout", message = "8s")),
        ErrorFrame(code = "bad_request", msg = "bad", message = "bad", reqId = "r12"),
    )

    private val eventFrames: List<EventFrame> = listOf(
        OrchDispatch(runId = "run-1", taskId = null, ref = "vh-ref", credentials = JsonPrimitive("cred"), lane = "liaison", mode = "voice", ts = 1788135286.5),
        OrchProgress(dispatchId = "d-1", lines = 42, head = "tail line", ts = 1788135286.5),
        OrchDone(ref = "vh-ref", runId = "run-1", body = "final body", status = null, ts = 1788135286.5),
        OrchDone(ref = "vh-ref2", runId = "run-2", body = null, status = "cancelled", ts = 1788135286.5),
        OrchFailed(ref = "vh-ref3", runId = "run-3", reason = "spawn failed", ts = 1788135286.5),
        OrchAck(raw = buildJsonObject { put("future", "x") }, ts = null),
        OrchGate(raw = buildJsonObject { put("payload", 1) }, ts = 1788135286.5),
        OrchMetrics(raw = buildJsonObject { }, ts = null),
        HeadTurn(convId = "conv-42", phase = "user_text", detail = "hello", ts = 1788135286.5),
        HeadCompact(convId = "conv-42", beforeChars = 1000, afterChars = 400, pinned = true, reason = "threshold", ts = 1788135286.5),
        FleetSnapshot(payload = buildJsonObject { put("seats", 1) }, ts = 1788135286.5),
        BridgeMsg(line = "MSGBR] ping", offset = 42, ts = 1788135286.5),
        TicketsSnapshot(payload = JsonPrimitive("# tickets full text"), ts = 1788135286.5),
        BodyPush(ref = "vh-1a2b3c4d", no = 3, status = "done", title = "T", summary = "s", chars = 99, inline = "text", ts = 1788135286.5),
        BodyPush(items = listOf(BodyIndexRow(no = 2, ref = "vh-1a2b3c4d", status = "done", title = "T", summary = "s", chars = 98, ts = 1788135286.5)), ts = 1788135286.5),
        PmEvent(kind = "ledger.commit", msgid = "m-1", source = "ledger", seq = 7, path = "/ledger", replay = true, ref = "vh-9", tool = "ledger", args = buildJsonObject { put("op", "bump") }, status = "done", exitCode = 0, ms = 12.5, err = null, ts = 1788135286.5),
    )

    @Test fun clientFrameRoundtrip() {
        assertEquals(18, clientFrames.map { it.t }.toSet().size)
        clientFrames.forEach(::roundtrip)
    }

    @Test fun serverFrameRoundtrip() {
        assertEquals(16, serverFrames.map { it.t }.toSet().size)
        serverFrames.forEach(::roundtrip)
    }

    @Test fun eventFrameRoundtrip() {
        assertEquals(14, eventFrames.map { it.t }.toSet().size)
        eventFrames.forEach(::roundtrip)
    }

    // ---- spec-literal decode samples (wire shapes straight from the frozen doc) ----

    @Test fun authOkCarriesV1Proto() {
        val f = FrameCodec.decode("""{"t":"auth.ok","session_id":"s-1a2b3c4d","proto":"v1"}""")
        assertEquals(AuthOk(sessionId = "s-1a2b3c4d", proto = "v1"), f)
    }

    @Test fun errorFrameDualFieldPrecedence() {
        val f = FrameCodec.decode(
            """{"t":"error","code":"bad_state","msg":"already authed","type":"error","message":"already authed"}""",
        ) as ErrorFrame
        assertEquals("bad_state", f.code)
        assertEquals("already authed", f.msg)
        assertEquals("already authed", f.message)
        assertEquals("error", f.type)
    }

    @Test fun fleetBriefResultKeepsCamelCaseSeatFields() {
        val f = FrameCodec.decode(
            """{"t":"fleet.brief.result","req_id":"r5","seats":[{"code":"c1","sessionId":"s1","live":true,"running":false,"idle_s":3,"last_seen":1788135286.0}],"inactive":1,"liaison":{"bound":false}}""",
        ) as FleetBriefResult
        assertEquals("s1", f.seats.single().sessionId)
        assertEquals(3.0, f.seats.single().idleS!!, 0.0)
    }

    @Test fun fleetCleanupResultReadsHyphenatedKey() {
        val f = FrameCodec.decode(
            """{"t":"fleet.cleanup.result","req_id":"r4","results":[{"id":"t1","ok":true},{"id":"t2","ok":false,"error":"end-failed","note":"active-liaison","binding-cleared":true}],"failed":1}""",
        ) as FleetCleanupResult
        assertEquals(true, f.results[1].bindingCleared)
        assertEquals("active-liaison", f.results[1].note)
    }

    @Test fun pmResErrorEnvelope() {
        val f = FrameCodec.decode(
            """{"t":"pm.res","id":"op-1","error":{"code":"pm_timeout","message":"8s"}}""",
        ) as PmRes
        assertEquals(PmError("pm_timeout", "8s"), f.error)
    }

    @Test fun intLiteralToleratedForEpochFields() {
        val f = FrameCodec.decode("""{"t":"pong","ts":1788135286}""") as Pong
        assertEquals(1788135286.0, f.ts, 0.0)
    }
}
