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
        val written = mutableListOf<Int>() // per-write byte counts
        override fun play() { played++ }
        override fun pause() { paused++ }
        override fun flush() { flushed++ }
        override fun stop() {}
        override fun release() { released++ }
        override fun setVolume(v: Float) { volume = v }
        override fun write(bytes: ByteArray): Int { written.add(bytes.size); return bytes.size }
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
}
