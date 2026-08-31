package dev.dshpm.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** 200 ms coalescing, tk BLOCK alignment (4 × 800 samples = 3200). */
class ChunkCoalescerTest {

    private fun block(n: Int = ChunkCoalescer.BLOCK_SAMPLES) = ByteArray(n * 2) { (it % 251).toByte() }

    @Test
    fun fourBlocksMakeOneSend() {
        val out = mutableListOf<ByteArray>()
        val c = ChunkCoalescer(sink = { out.add(it) })
        repeat(4) { c.add(block()) }
        assertEquals(1, out.size)
        assertEquals(ChunkCoalescer.SEND_BYTES, out[0].size)
    }

    @Test
    fun partialRemainderFlushesOnPttRelease() {
        val out = mutableListOf<ByteArray>()
        val c = ChunkCoalescer(sink = { out.add(it) })
        repeat(3) { c.add(block()) } // 150 ms — below one send
        assertEquals(0, out.size)
        c.flushRemainder()
        assertEquals(1, out.size)
        assertEquals(3 * ChunkCoalescer.BLOCK_SAMPLES * 2, out[0].size)
    }

    @Test
    fun overflowSpansSendsAndResetClears() {
        val out = mutableListOf<ByteArray>()
        val c = ChunkCoalescer(sink = { out.add(it) })
        repeat(9) { c.add(block()) } // 2 full sends + 1 block remainder
        assertEquals(2, out.size)
        c.reset()
        c.flushRemainder()
        assertEquals(2, out.size) // nothing left after reset
    }
}
