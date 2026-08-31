package dev.dshpm.proto.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PM-007 semantics: (source,msgid) key, 60s TTL, >1000 → halve GC. */
class MsgIdDedupTest {

    @Test fun sameSourceAndMsgidIsDuplicate() {
        val d = MsgIdDedup { 1000L }
        assertFalse(d.seen("ledger", "m1"))
        assertTrue(d.seen("ledger", "m1"))
        assertEquals(1, d.duplicateCount)
    }

    @Test fun sameMsgidDifferentSourceIsNotDuplicate() {
        val d = MsgIdDedup { 1000L }
        assertFalse(d.seen("ledger", "m1"))
        assertFalse(d.seen("flow", "m1")) // (source,msgid) is the key, not bare msgid
    }

    @Test fun nullMsgidNeverDeduplicates() {
        val d = MsgIdDedup { 1000L }
        assertFalse(d.seen("ledger", null))
        assertFalse(d.seen("ledger", null))
        assertEquals(0, d.duplicateCount)
    }

    @Test fun expiredEntriesLeaveTheWindow() {
        var now = 1000L
        val d = MsgIdDedup(ttlMillis = 60_000, gcThreshold = 1000) { now }
        assertFalse(d.seen("ledger", "m1"))
        now += 60_001 // past the 60s window
        assertFalse(d.seen("ledger", "m1")) // no longer a duplicate
    }

    @Test fun windowHoldsWithinTtl() {
        var now = 1000L
        val d = MsgIdDedup(ttlMillis = 60_000, gcThreshold = 1000) { now }
        assertFalse(d.seen("ledger", "m1"))
        now += 59_999
        assertTrue(d.seen("ledger", "m1")) // still inside the window
    }

    @Test fun oversizeWindowTruncatesToNewestHalf() {
        var now = 1000L
        val d = MsgIdDedup(ttlMillis = 60_000, gcThreshold = 4) { now }
        for (i in 1..5) {
            now += 10 // strictly increasing insert times
            assertFalse(d.seen("ledger", "m$i"))
        }
        assertTrue("expected halved window, was ${d.size()}", d.size() <= 2)
        // newest half kept: m4/m5 still duplicates; m1/m2 re-deliverable
        assertTrue(d.seen("ledger", "m5"))
        assertTrue(d.seen("ledger", "m4"))
        assertFalse(d.seen("ledger", "m1"))
        assertFalse(d.seen("ledger", "m2"))
    }

    @Test fun resetClearsEverything() {
        val d = MsgIdDedup { 1000L }
        d.seen("ledger", "m1")
        d.reset()
        assertEquals(0, d.duplicateCount)
        assertFalse(d.seen("ledger", "m1"))
    }
}
