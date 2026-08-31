package dev.dshpm.android.voice

/**
 * Mic capture abstraction (AND4-2 ②): production = AudioRecord reader thread;
 * unit tests inject a fake source. Blocks are 800-sample (50 ms) int16 mono
 * LE — the tk BLOCK alignment the coalescer expects.
 */
interface MicSource {
    /** Begin capturing; blocks flow into [onBlock] until [stop]. Idempotent. */
    fun start(onBlock: (ByteArray) -> Unit)

    /** Stop capturing. Idempotent. Must not drop the calling thread. */
    fun stop()

    /** Release underlying hardware (tab exit). */
    fun close()
}

/**
 * Playback sink abstraction (AND4-2 ③): production = AudioTrack worker thread
 * with tk's sentinel semantics — the receive loop ONLY enqueues; interrupt
 * clears the queue FIRST and then posts a drop sentinel so the worker breaks
 * its stream immediately (a sentinel queued behind stale audio would wait out
 * the whole backlog — see tk `_drop_playback`).
 */
interface PlayerSink {
    /** Enqueue a downlink PCM chunk (24 kHz int16 mono). No-op when muted upstream. */
    fun enqueue(pcm: ByteArray)

    /** Interrupt: clear pending audio, then break the device stream (immediate silence). */
    fun dropAll()

    /** Release the device (tab exit). */
    fun close()
}
