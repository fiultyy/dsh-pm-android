package dev.dshpm.android.pm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers bound to the live op-endpoint wire shapes probed 2026-08-31. */
class PmParsingTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).let { it as kotlinx.serialization.json.JsonObject }

    @Test
    fun parseTicketsRealShape() {
        val data = obj(
            """{"op":"tickets","count":3,"tickets":[
                {"ticket_id":"AND1-1","title":"冻结文档","state":"done","deps":"[]","lease_owner":null,"refs":"{}","outcome":null,"updated_at":"2026-08-30T16:37:21+00:00"},
                {"ticket_id":"AND2-3","title":"协议绑定","state":"doing","deps":"[\"AND1-1\"]","lease_owner":"a804","refs":"{}","outcome":null,"updated_at":"2026-08-31T00:20:00+00:00"},
                {"ticket_id":"AND3-1","title":"三视图","state":"todo","deps":"[\"AND1-1\",\"AND2-3\"]","lease_owner":null,"refs":"{}","outcome":null,"updated_at":"2026-08-31T01:00:00+00:00"}
            ]}""",
        )
        val groups = PmParsing.parseTickets(data)
        assertEquals(3, groups.size)
        assertEquals(listOf("done", "doing", "todo"), groups.map { it.state }) // first-appearance order
        assertEquals(1, groups[0].count)
        val doing = groups[1].tickets.single()
        assertEquals("AND2-3", doing.ticketId)
        assertEquals(listOf("AND1-1"), doing.deps)
        assertEquals("a804", doing.leaseOwner)
        val todo = groups[2].tickets.single()
        assertEquals(listOf("AND1-1", "AND2-3"), todo.deps)
        assertNull(todo.leaseOwner)
    }

    @Test
    fun depsUnwrapToleratesGarbage() {
        assertEquals(emptyList<String>(), PmParsing.parseDepsString(null))
        assertEquals(emptyList<String>(), PmParsing.parseDepsString("[]"))
        assertEquals(emptyList<String>(), PmParsing.parseDepsString("not json"))
        assertEquals(listOf("A", "B"), PmParsing.parseDepsString("""["A","B"]"""))
    }

    private fun row(id: String, state: String, updatedAt: String? = null) =
        TicketRow(id, "t-$id", state, emptyList(), null, updatedAt)

    @Test
    fun groupOrderFollowsFixedPriorityNotAppearance() {
        // Live-like shape: done masses lead the service array (41 张置顶误判源),
        // live states scattered — board must surface dispatched first regardless.
        val tickets = listOf(
            row("D1", "done"), row("D2", "done"), row("M1", "merged"),
            row("R1", "rejected"), row("B1", "blocked"), row("D3", "done"),
            row("W1", "dispatched"), row("W2", "running"), row("X1", "rolled-back"),
        )
        val groups = PmParsing.groupByState(tickets)
        // priority first; unlisted states (rolled-back) append in first-appearance order
        assertEquals(
            listOf("dispatched", "running", "blocked", "done", "merged", "rejected", "rolled-back"),
            groups.map { it.state },
        )
        assertEquals(3, groups.first { it.state == "done" }.count)
        assertEquals("W1", groups[0].tickets.single().ticketId)
    }

    @Test
    fun withinGroupNewestUpdatedFirstMissingStampsKeepServiceOrder() {
        val tickets = listOf(
            row("old", "running", "2026-08-30T10:00:00Z"),
            row("nostamp1", "running"), // no stamp → keeps service position among unstamped
            row("newest", "running", "2026-08-31T09:00:00Z"),
            row("nostamp2", "running"),
            row("garbage", "running", "not-a-date"), // unparseable → treated as absent
        )
        val ids = PmParsing.groupByState(tickets).single().tickets.map { it.ticketId }
        // stamped rows newest-first; unstamped/unparseable keep their service order at the tail
        assertEquals(listOf("newest", "old", "nostamp1", "nostamp2", "garbage"), ids)
    }

    @Test
    fun parseFleetRealShape() {
        val data = obj(
            """{"op":"fleet","count":2,"seats":[
                {"code":"9b8b","sessionId":"s1","role":"worker","node":"pmw1-verify","preset":"maestro","spawnedAt":"2026-08-30T15:22:28+00:00","status":"active","session":{"running":false,"blank":false,"agentPreset":"maestro","cwd":"/home/yy/tools/dsh-pm","title":"9b8b-pmw1-verify · maestro · pm-web 全通验收(PMW1-3)(active)"}},
                {"code":"a804","sessionId":"s2","role":"worker","node":"and2-repo","preset":"maestro","spawnedAt":"2026-08-30T23:56:18+00:00","status":"active","session":{"running":true,"title":"a804-and2-repo · maestro · dsh-pm-android 仓初始化(AND2-2)(active)"}}
            ],"degraded":false,"sessionJoined":true,"note":""}""",
        )
        val seats = PmParsing.parseFleetSeats(data)
        assertEquals(2, seats.size)
        assertEquals("9b8b", seats[0].code)
        assertEquals("pmw1-verify", seats[0].node)
        assertNotNull(seats[0].title)
        assertTrue(seats[0].title!!.contains("pm-web 全通验收"))
        assertEquals(false, PmParsing.parseFleetDegraded(data))
        assertNull(PmParsing.parseFleetNote(data)) // blank note → null
    }

    @Test
    fun parseFleetEmptyGivesExplicitEmptyState() {
        val data = obj("""{"op":"fleet","count":0,"seats":[],"degraded":false,"sessionJoined":false,"note":"dsh api 8s 超时"}""")
        assertTrue(PmParsing.parseFleetSeats(data).isEmpty())
        assertEquals("dsh api 8s 超时", PmParsing.parseFleetNote(data))
    }

    @Test
    fun parseFlowsRealShape() {
        val data = obj(
            """{"op":"flow","count":1,"flows":[
                {"flow":"and1","source":"sql","degraded":false,"nodes":[
                    {"node_id":"and1-final","verb":"rollup","title":"立项闸汇总","state":"done","attempts":0,"result":"rollup","events":2},
                    {"node_id":"and1-freeze-c","verb":"callback","title":"冻结门","state":"done","attempts":0,"result":"过","events":2}
                ],"rollup":[{"state":"done","n":5}]}
            ]}""",
        )
        val flows = PmParsing.parseFlows(data)
        assertEquals(1, flows.size)
        val f = flows[0]
        assertEquals("and1", f.flow)
        assertEquals(2, f.nodes.size)
        assertEquals("and1-final", f.nodes[0].nodeId)
        assertEquals("rollup", f.nodes[0].verb)
        assertEquals("done", f.nodes[0].state)
    }

    // ---- relative duration (席位 lastSeen/spawned 相对时长) ----

    private val now = java.time.Instant.parse("2026-08-31T02:00:00Z").toEpochMilli()

    @Test
    fun relativeDurationBoundaries() {
        assertEquals("—", PmParsing.relativeDuration(null, now))
        assertEquals("—", PmParsing.relativeDuration("garbage", now))
        assertEquals("just now", PmParsing.relativeDuration("2026-08-31T01:59:55Z", now))
        assertEquals("45s ago", PmParsing.relativeDuration("2026-08-31T01:59:15Z", now))
        assertEquals("1m ago", PmParsing.relativeDuration("2026-08-31T01:59:00Z", now))
        assertEquals("59m ago", PmParsing.relativeDuration("2026-08-31T01:01:00Z", now))
        assertEquals("1h ago", PmParsing.relativeDuration("2026-08-31T01:00:00Z", now))
        assertEquals("3h ago", PmParsing.relativeDuration("2026-08-30T23:00:00Z", now))
        assertEquals("2d ago", PmParsing.relativeDuration("2026-08-29T02:00:00Z", now))
        assertEquals("—", PmParsing.relativeDuration("2026-08-31T03:00:00Z", now)) // future → "—"
    }

    @Test
    fun relativeDurationHandlesOffsetStamps() {
        // real fleet stamps carry +00:00 offsets, not Z
        assertEquals("1h ago", PmParsing.relativeDuration("2026-08-31T01:00:00+00:00", now))
    }
}
