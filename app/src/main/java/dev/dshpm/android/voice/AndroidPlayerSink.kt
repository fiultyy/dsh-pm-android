package dev.dshpm.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Testable AudioTrack abstraction (AND4-3 ②d): production wraps a real
 * MODE_STREAM track; unit tests inject a counting mock.
 */
interface AudioTrackHandle {
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
    fun setVolume(v: Float)

    /** Blocking slice write; returns bytes accepted (may be short, 0/negative = failure). */
    fun write(bytes: ByteArray, offset: Int, size: Int): Int

    /** Whole-array convenience — delegates to the slice write. */
    fun write(bytes: ByteArray): Int = write(bytes, 0, bytes.size)
}

interface TrackFactory {
    /** null = device unavailable (worker drops chunks and continues). */
    fun create(sampleRate: Int, bufferFrames: Int): AudioTrackHandle?
}

/** Production factory: USAGE_MEDIA + CONTENT_TYPE_SPEECH (②a — explicitly NOT
 *  USAGE_VOICE_COMMUNICATION, which routes to the near-silent earpiece). */
class AudioTrackTrackFactory : TrackFactory {
    override fun create(sampleRate: Int, bufferFrames: Int): AudioTrackHandle? = runCatching {
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            // API order: (attributes, format, bufferSizeInBytes, mode, sessionId)
            // — buffer BEFORE mode. The swapped order passed mode=bufferBytes and
            // threw "Invalid mode" on every create (exposed by AND4-4 yield: the
            // mute window used to eat the downlink before a track was ever needed).
            bufferFrames * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.setVolume(1.0f) // ②e: explicit full track gain
        RealHandle(track)
    }.onFailure {
        // ②d diagnosability: a silent create() null reads as a dead device;
        // the actual throwable is the fork between config vs device failure.
        Log.w(AndroidPlayerSink.TAG, "AudioTrack create failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    private class RealHandle(private val track: AudioTrack) : AudioTrackHandle {
        override fun play() = track.play()
        override fun pause() = track.pause()
        override fun flush() = track.flush()
        override fun stop() = track.stop()
        override fun release() = track.release()
        override fun setVolume(v: Float) { track.setVolume(v) }

        override fun write(bytes: ByteArray, offset: Int, size: Int): Int =
            // explicit mode: the 3-arg legacy write throws "Invalid mode" on API 36
            track.write(bytes, offset, size, AudioTrack.WRITE_BLOCKING)
    }
}

/**
 * AudioTrack-backed player (AND4-2 ③ / AND4-3 ②): 24 kHz int16 mono downlink
 * TTS. tk `_DROP_PLAYBACK` semantics — the WS receive loop only enqueues;
 * this worker owns the device. Interrupt clears the queue FIRST, then posts
 * the sentinel. Write accounting (②d) logs byte totals so "frames arrived
 * but silent" is diagnosable from logcat alone.
 */
class AndroidPlayerSink(
    private val factory: TrackFactory = AudioTrackTrackFactory(),
    private val sampleRate: Int = SAMPLE_RATE,
) : PlayerSink {

    private sealed interface Item {
        data class Pcm(val bytes: ByteArray) : Item
        object Drop : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null

    /** Bytes successfully handed to the device (observability; read after close or mid-run). */
    val writtenBytes = AtomicLong(0)
    val writeCalls = AtomicLong(0)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread = Thread({
            var track: AudioTrackHandle? = null
            var lastLogWritten = 0L
            // AND4-6: the drop sentinel pauses+flushes the live track; without
            // a resume the next utterance only fills the 9.6 kB buffer and
            // every later reply stays silent (真机 89 帧全到无声 case).
            var pausedAfterDrop = false
            Log.i(TAG, "playback worker up: ${sampleRate}Hz mono int16")
            while (started.get()) {
                val item = try {
                    queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    break
                }
                when (item) {
                    is Item.Drop -> {
                        track?.let {
                            runCatching { it.pause() }
                            runCatching { it.flush() } // discard buffered samples
                        }
                        pausedAfterDrop = track != null
                        queue.clear() // drop everything queued behind us too
                        Log.i(TAG, "playback dropped (interrupt sentinel); written so far ${writtenBytes.get()}B")
                    }
                    is Item.Pcm -> {
                        try {
                            if (track == null) {
                                track = factory.create(sampleRate, sampleRate / 5)?.also {
                                    it.setVolume(1.0f) // ②e explicit full gain
                                    it.play()
                                    Log.i(TAG, "AudioTrack created+playing (USAGE_MEDIA/SPEECH, vol=1.0)")
                                }
                                pausedAfterDrop = false // fresh track already playing
                            }
                            if (track != null) {
                                if (pausedAfterDrop) {
                                    // AND4-6: resume BEFORE writing — a paused
                                    // MODE_STREAM track never drains its buffer.
                                    track.play()
                                    pausedAfterDrop = false
                                    Log.i(TAG, "playback resumed after interrupt drop")
                                }
                                var offset = 0
                                while (offset < item.bytes.size) {
                                    val n = track.write(item.bytes, offset, item.bytes.size - offset)
                                    if (n <= 0) {
                                        Log.w(TAG, "write returned $n at offset $offset/${item.bytes.size}")
                                        break
                                    }
                                    offset += n
                                    writeCalls.incrementAndGet()
                                    writtenBytes.addAndGet(n.toLong())
                                    // ②d: periodic write accounting (every ~1s of audio)
                                    if (writtenBytes.get() - lastLogWritten >= sampleRate * 2) {
                                        lastLogWritten = writtenBytes.get()
                                        Log.i(TAG, "write accounting: ${writeCalls.get()} calls, ${writtenBytes.get()}B (${writtenBytes.get() * 1000 / (sampleRate * 2)}ms audio)")
                                    }
                                }
                            } else {
                                Log.w(TAG, "no track (device unavailable); dropped ${item.bytes.size}B")
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "playback write failed: ${t.message}")
                            runCatching { track?.release() }
                            track = null
                            pausedAfterDrop = false
                        }
                    }
                }
            }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            Log.i(TAG, "playback worker down; total written ${writtenBytes.get()}B in ${writeCalls.get()} writes")
        }, "dshpm-play").apply { isDaemon = true; start() }
    }

    override fun enqueue(pcm: ByteArray) {
        if (started.get()) queue.put(Item.Pcm(pcm))
    }

    override fun dropAll() {
        queue.clear() // stale audio first…
        if (started.get()) queue.put(Item.Drop) // …then break the stream
    }

    override fun close() {
        started.set(false)
        thread?.join(1500)
        thread = null
        queue.clear()
    }

    companion object {
        const val TAG = "DshPmVoice"
        const val SAMPLE_RATE = 24_000 // gateway downlink TTS rate (tk OUT_RATE)
    }
}
