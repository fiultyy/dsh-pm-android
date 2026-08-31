package dev.dshpm.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AND4-3 ②: playback chain against a mock track — write accounting (②d),
 * drop-sentinel semantics, device-unavailable tolerance.
 */
class AndroidPlayerSinkTest {

    private class MockTrack : AudioTrackHandle {
        var created = 0; var played = 0; var paused = 0; var flushed = 0; var released = 0
        var volume: Float? = null
        val written = mutableListOf<Int>() // per-write accepted byte counts
        val events = mutableListOf<String>() // "play" | "pause" | "write:n" — ordering proof
        var partialWrites: ((callIndex: Int, requested: Int) -> Int)? = null
        private var calls = 0
        override fun play() { played++; events.add("play") }
        override fun pause() { paused++; events.add("pause") }
        override fun flush() { flushed++ }
        override fun stop() {}
        override fun release() { released++ }
        override fun setVolume(v: Float) { volume = v }
        override fun write(bytes: ByteArray, offset: Int, size: Int): Int {
            calls++
            val n = partialWrites?.invoke(calls, size) ?: size
            written.add(n)
            events.add("write:$n")
            return n
        }
    }

    private class MockFactory(private val track: MockTrack?) : TrackFactory {
        var creations = 0
        override fun create(sampleRate: Int, bufferFrames: Int): AudioTrackHandle? {
            creations++
            return track
        }
    }

    private fun pcm(n: Int) = ByteArray(n)

    @Test
    fun enqueuedAudioIsWrittenAndAccounted() {
        val track = MockTrack()
        val factory = MockFactory(track)
        val sink = AndroidPlayerSink(factory = factory, sampleRate = 24_000)
        sink.start()
        sink.enqueue(pcm(1000))
        sink.enqueue(pcm(500))
        // wait until both chunks are written
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sink.writtenBytes.get() < 1500 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(1500L, sink.writtenBytes.get())
        assertEquals(2, track.written.size)
        assertEquals(1, factory.creations) // one lazily-created track serves the stream
        assertEquals(1, track.played)
        assertEquals(1.0f, track.volume) // ②e explicit full gain
        sink.close()
    }

    @Test
    fun dropSentinelPausesFlushesAndClearsBacklog() {
        val track = MockTrack()
        val sink = AndroidPlayerSink(factory = MockFactory(track), sampleRate = 24_000)
        sink.start()
        // audio flowing first — the interrupt must land on a LIVE track
        sink.enqueue(pcm(4_800))
        val warm = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (sink.writtenBytes.get() < 4_800 && System.nanoTime() < warm) Thread.sleep(20)
        assertEquals(4_800L, sink.writtenBytes.get())
        // stale audio still queued when the interrupt lands
        sink.enqueue(pcm(10_000))
        sink.enqueue(pcm(10_000))
        sink.dropAll()
        sink.enqueue(pcm(10_000)) // post-interrupt chunk (queued after the sentinel)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (track.paused == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("sentinel must pause+flush the track", track.paused >= 1 && track.flushed >= 1)
        // the pre-drop backlog never reaches the device…
        val settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < settle) Thread.sleep(20)
        assertTrue("backlog cleared, at most the post-drop chunk may play", sink.writtenBytes.get() <= 4_800 + 10_000)
        sink.close()
    }

    @Test
    fun noTrackDropsChunksWithoutCrashing() {
        val sink = AndroidPlayerSink(factory = MockFactory(null), sampleRate = 24_000)
        sink.start()
        sink.enqueue(pcm(4800))
        sink.enqueue(pcm(4800))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (sink.writeCalls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0L, sink.writtenBytes.get()) // nothing written — but no crash
        sink.close()
    }

    // -------------------------------------------- AND4-6 打断后恢复 play

    /** After a drop sentinel the track is paused; the NEXT utterance must
     *  resume play BEFORE any write, else the buffer never drains. */
    @Test
    fun resumeAfterDropPlaysBeforeWrite() {
        val track = MockTrack()
        val sink = AndroidPlayerSink(factory = MockFactory(track), sampleRate = 24_000)
        sink.start()
        sink.enqueue(pcm(4_800)) // warm: track created + playing
        awaitWritten(sink, 4_800)
        assertEquals(1, track.played)

        sink.dropAll() // interrupt — pauses the LIVE track
        awaitCondition { track.paused >= 1 } // sentinel consumed (clears stale backlog)
        sink.enqueue(pcm(2_000)) // next utterance arrives — after the sentinel
        awaitCondition { track.paused >= 1 && track.played >= 2 }

        // ordering contract: between the pause and the resume play there is
        // NO write, and the resume play is followed by writes
        val lastPause = track.events.lastIndexOf("pause")
        val resumePlay = track.events
            .withIndex().firstOrNull { it.index > lastPause && it.value == "play" }?.index ?: -1
        assertTrue("must resume play after the drop pause", resumePlay > lastPause)
        val writesAfterResume = track.events.drop(resumePlay + 1).filter { it.startsWith("write:") }
        assertTrue("writes must follow the resume play", writesAfterResume.isNotEmpty())
        assertEquals("no write may squeeze between pause and resume", 0,
            track.events.subList(lastPause + 1, resumePlay).count { it.startsWith("write:") })
        sink.close()
    }

    /** Two consecutive interrupt rounds in one session: play each time on the
     *  SAME track (no recreate) — the exact 真机 two-round scenario. */
    @Test
    fun twoConsecutiveInterruptRoundsBothResume() {
        val track = MockTrack()
        val factory = MockFactory(track)
        val sink = AndroidPlayerSink(factory = factory, sampleRate = 24_000)
        sink.start()
        // round 1: reply plays
        sink.enqueue(pcm(3_000))
        awaitWritten(sink, 3_000)
        // round 2 begins with a barge-in drop, then fresh reply audio
        sink.dropAll()
        awaitCondition { track.paused >= 1 } // sentinel consumed
        sink.enqueue(pcm(2_500))
        awaitCondition { sink.writtenBytes.get() >= 3_000 + 2_500 }
        // round 3: a second interrupt in the same session
        sink.dropAll()
        awaitCondition { track.paused >= 2 } // second sentinel consumed
        sink.enqueue(pcm(1_500))
        awaitCondition { sink.writtenBytes.get() >= 3_000 + 2_500 + 1_500 }
        assertEquals("each interrupt round must resume play", 3, track.played)
        assertEquals(2, track.paused)
        assertEquals("same track reused — no recreate between rounds", 1, factory.creations)
        assertEquals(7_000L, sink.writtenBytes.get())
        sink.close()
    }

    /** Short writes: the worker loops until the chunk is fully accepted. */
    @Test
    fun shortWritesAreLoopedToCompletion() {
        val track = MockTrack()
        track.partialWrites = { call, requested -> if (call == 1) requested / 2 else requested }
        val sink = AndroidPlayerSink(factory = MockFactory(track), sampleRate = 24_000)
        sink.start()
        sink.enqueue(pcm(1_000))
        awaitWritten(sink, 1_000)
        assertEquals(listOf(500, 500), track.written) // half, then the rest
        assertEquals(1_000L, sink.writtenBytes.get())
        sink.close()
    }

    /** A 0-returning write breaks the loop instead of spinning forever. */
    @Test
    fun zeroWriteBreaksLoopWithoutHanging() {
        val track = MockTrack()
        track.partialWrites = { _, _ -> 0 }
        val sink = AndroidPlayerSink(factory = MockFactory(track), sampleRate = 24_000)
        sink.start()
        sink.enqueue(pcm(1_000))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (track.written.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        Thread.sleep(300) // give the worker time to process (and break) — must not hang
        assertTrue("loop must break on n<=0", sink.writeCalls.get() <= 1)
        assertEquals(0L, sink.writtenBytes.get())
        sink.close()
    }

    private fun awaitWritten(sink: AndroidPlayerSink, bytes: Long) = awaitCondition { sink.writtenBytes.get() >= bytes }

    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}
