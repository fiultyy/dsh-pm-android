package dev.dshpm.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

/**
 * AudioTrack-backed player (AND4-2 ③): 24 kHz int16 mono downlink TTS.
 *
 * tk `_DROP_PLAYBACK` semantics, Android flavour: the WS receive loop only
 * enqueues; this worker owns the device. Interrupt = clear the queue FIRST,
 * then post the sentinel — a sentinel queued behind stale audio would wait
 * out the whole backlog. On the sentinel the worker pauses + flushes the
 * AudioTrack (flush discards already-buffered samples → immediate silence).
 */
class AndroidPlayerSink : PlayerSink {

    private sealed interface Item {
        data class Pcm(val bytes: ByteArray) : Item
        object Drop : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread = Thread({
            var track: AudioTrack? = null
            Log.i(TAG, "playback worker up: ${SAMPLE_RATE}Hz mono int16")
            while (started.get()) {
                val item = try {
                    queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    break
                }
                when (item) {
                    is Item.Drop -> {
                        track?.let {
                            runCatching { it.pause() }
                            runCatching { it.flush() } // discard buffered samples
                        }
                        queue.clear() // drop everything queued behind us too
                        Log.i(TAG, "playback dropped (interrupt sentinel)")
                    }
                    is Item.Pcm -> {
                        try {
                            if (track == null) {
                                track = AudioTrack(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build(),
                                    AudioFormat.Builder()
                                        .setSampleRate(SAMPLE_RATE)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build(),
                                    AudioTrack.MODE_STREAM,
                                    SAMPLE_RATE / 5, // 200 ms device buffer
                                    AudioManager.AUDIO_SESSION_ID_GENERATE,
                                ).also { it.play(); Log.i(TAG, "AudioTrack started") }
                            }
                            // explicit write mode: the 3-arg legacy write throws
                            // "Invalid mode" on API 36 (observed on NE2210)
                            track.write(item.bytes, 0, item.bytes.size, AudioTrack.WRITE_BLOCKING)
                        } catch (t: Throwable) {
                            Log.e(TAG, "playback write failed: ${t.message}")
                            runCatching { track?.release() }
                            track = null
                        }
                    }
                }
            }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            Log.i(TAG, "playback worker down")
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
