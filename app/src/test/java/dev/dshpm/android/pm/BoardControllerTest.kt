package dev.dshpm.android.pm

import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.PmEvent
import dev.dshpm.proto.frame.PmReq
import dev.dshpm.proto.frame.PmRes
import dev.dshpm.proto.frame.PmSub
import dev.dshpm.proto.ws.ConnectionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BoardController (view-model) tests: connected-cycle actions, pm.req
 * correlation, event→refresh merge, re-subscribe on pm_sub_ended.
 */
class BoardControllerTest {

    private class Harness {
        val sent = mutableListOf<dev.dshpm.proto.frame.ClientFrame>()
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = BoardController(
            sendPort = { sent.add(it) },
            schedule = { d, a -> scheduled.add(d to a) },
            nowMs = { 1_000L },
        )

        fun runScheduled() {
            val copy = scheduled.toList()
            scheduled.clear()
            copy.forEach { it.second() }
        }

        /** Feed a pm.res the way the gateway echoes it (id verbatim). */
        fun feedRes(id: String, op: String, data: String? = null) {
            val payload = if (data != null) {
                Json.parseToJsonElement(data)
            } else {
                buildJsonObject {
                    put("code", "pm_timeout")
                    put("message", "8s")
                }
            }
            val res = if (data != null) {
                PmRes(id = JsonPrimitive(id.toInt()), data = payload)
            } else {
                PmRes(id = JsonPrimitive(id.toInt()), error = dev.dshpm.proto.frame.PmError("pm_timeout", "8s"))
            }
            controller.onFrame(res)
            assertEquals(op, op) // marker; real assertion via uiState below
        }
    }

    private fun ticketsData() =
        """{"op":"tickets","count":1,"tickets":[
            {"ticket_id":"AND3-1","title":"三视图","state":"doing","deps":"[\"AND2-3\"]","lease_owner":"a804","refs":"{}","outcome":null,"updated_at":"2026-08-31T01:00:00+00:00"}]}"""

    @Test
    fun connectedCyclePullsAllThreeOpsAndSubscribes() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)

        val reqs = h.sent.filterIsInstance<PmReq>()
        assertEquals(listOf("tickets", "fleet", "flow"), reqs.map { it.op })
        assertEquals(3, reqs.map { it.id.content }.toSet().size) // distinct ids
        val sub = h.sent.filterIsInstance<PmSub>().single()
        assertEquals(listOf("tickets", "fleet", "flow", "act"), sub.kinds) // spec §2 订阅口径
    }

    @Test
    fun pmResCorrelatesByIdAndPopulatesTickets() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        val ticketsReq = h.sent.filterIsInstance<PmReq>().first { it.op == "tickets" }

        h.feedRes(ticketsReq.id.content, "tickets", ticketsData())

        val s = h.controller.uiState
        assertTrue(s.ticketsLoaded)
        assertEquals(1, s.ticketGroups.size)
        assertEquals("doing", s.ticketGroups[0].state)
        assertEquals("AND3-1", s.ticketGroups[0].tickets.single().ticketId)
        assertEquals(listOf("AND2-3"), s.ticketGroups[0].tickets.single().deps)
        assertEquals(1_000L, s.lastUpdateMs)
    }

    @Test
    fun unknownResIdIsIgnored() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        h.feedRes("999", "tickets", ticketsData())
        assertTrue(!h.controller.uiState.ticketsLoaded)
    }

    @Test
    fun pmResErrorBecomesBannerNotCrash() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        val flowReq = h.sent.filterIsInstance<PmReq>().first { it.op == "flow" }
        h.feedRes(flowReq.id.content, "flow", null)
        val banner = h.controller.uiState.banner
        assertNotNull(banner)
        assertTrue(banner!!.contains("pm_timeout"))
    }

    @Test
    fun ticketsEventSchedulesDebouncedRefreshWithFreshId() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        val firstTicketsId = h.sent.filterIsInstance<PmReq>().first { it.op == "tickets" }.id.content

        h.controller.onFrame(PmEvent(kind = "tickets", msgid = "m1", source = "ledger", replay = false))
        assertEquals("tickets", h.controller.uiState.lastEventKind)

        // debounced action scheduled (300ms), not an immediate request
        assertTrue(h.sent.none { it is PmReq && it.op == "tickets" && it.id.content != firstTicketsId })
        h.runScheduled()

        val refresh = h.sent.filterIsInstance<PmReq>().filter { it.op == "tickets" && it.id.content != firstTicketsId }
        assertEquals(1, refresh.size)
        val newId = refresh.single().id.content
        assertTrue(newId.toLong() > firstTicketsId.toLong()) // 自增 id, 不复用 (spec §2)
    }

    @Test
    fun eventBurstCoalescesIntoOneRefresh() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        repeat(5) { i ->
            h.controller.onFrame(PmEvent(kind = "fleet", msgid = "f$i", source = "fleet", replay = true))
        }
        h.runScheduled()
        assertEquals(1, h.sent.filterIsInstance<PmReq>().count { it.op == "fleet" && it.id.content.toLong() > 3 })
    }

    @Test
    fun actEventDoesNotRefreshAnyView() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        h.controller.onFrame(PmEvent(kind = "act", msgid = "a1", source = "act", ref = "vh-1234abcd"))
        h.runScheduled()
        assertEquals(0, h.sent.filterIsInstance<PmReq>().count { it.id.content.toLong() > 3 })
        assertEquals("act", h.controller.uiState.lastEventKind)
    }

    @Test
    fun pmSubEndedTriggersAutoResubscribe() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        val subsBefore = h.sent.filterIsInstance<PmSub>().size

        h.controller.onFrame(ErrorFrame(code = "pm_sub_ended", msg = "stream ended", message = "stream ended"))
        h.runScheduled() // the 1s resubscribe schedule

        assertEquals(subsBefore + 1, h.sent.filterIsInstance<PmSub>().size)
        assertTrue(h.controller.uiState.banner!!.contains("pm_sub_ended"))
    }

    @Test
    fun concurrentLimitGetsHumanizedBanner() {
        val h = Harness()
        h.controller.onFrame(ErrorFrame(code = "concurrent_limit", msg = ">2", message = ">2"))
        assertTrue(h.controller.uiState.banner!!.contains("并发上限"))
    }

    @Test
    fun reconnectWaitShowsBannerAndNextConnectRepulls() {
        val h = Harness()
        h.controller.onState(ConnectionState.CONNECTED)
        h.controller.onState(ConnectionState.RECONNECT_WAIT)
        assertTrue(h.controller.uiState.banner!!.contains("重连"))
        assertEquals(ConnectionState.RECONNECT_WAIT, h.controller.uiState.connection)

        h.sent.clear()
        h.controller.onState(ConnectionState.CONNECTED)
        assertEquals(3, h.sent.filterIsInstance<PmReq>().size) // full re-pull
        assertEquals(1, h.sent.filterIsInstance<PmSub>().size)
    }
}
