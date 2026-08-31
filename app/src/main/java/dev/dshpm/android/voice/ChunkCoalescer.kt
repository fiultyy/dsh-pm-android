package dev.dshpm.android.voice

/**
 * Upstream chunk coalescer (tk alignment): AudioRecord reads 800-sample
 * (50 ms) blocks; four of them make one 200 ms send (3200 samples) — inside
 * the gateway's 32 KB/s upstream budget and coarse enough to avoid WS
 * frame flooding.
 *
 * Deterministic by BLOCKS_PER_SEND (not wall time): 4 × 50 ms = 200 ms
 * exactly, so no clock injection is needed and tests are exact.
 */
class ChunkCoalescer(
    private val sink: (ByteArray) -> Unit,
    private val sampleBytes: Int = 2, // int16 mono
) {
    private var buffer = ByteArray(0)

    fun add(block: ByteArray) {
        buffer += block
        while (buffer.size >= SEND_BYTES) {
            val out = buffer.copyOf(SEND_BYTES)
            buffer = buffer.copyOfRange(SEND_BYTES, buffer.size)
            sink(out)
        }
    }

    /** Flush a trailing partial block (PTT release) — sends even when short. */
    fun flushRemainder() {
        if (buffer.isNotEmpty()) {
            sink(buffer)
            buffer = ByteArray(0)
        }
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    companion object {
        const val BLOCK_SAMPLES = 800          // tk BLOCK: 16 kHz × 50 ms
        const val BLOCKS_PER_SEND = 4          // 200 ms per WS binary frame
        const val SEND_SAMPLES = BLOCK_SAMPLES * BLOCKS_PER_SEND
        const val SEND_BYTES = SEND_SAMPLES * 2
    }
}
