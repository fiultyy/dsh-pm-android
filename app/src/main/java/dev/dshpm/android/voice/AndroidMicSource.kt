package dev.dshpm.android.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecord-backed mic (AND4-2 ②): 16 kHz / mono / int16, reads in
 * 800-sample (50 ms) blocks — the tk BLOCK alignment. Reader thread owns the
 * record; [stop] flips the flag and joins briefly.
 */
class AndroidMicSource : MicSource {

    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission") // caller gates on runtime permission
    override fun start(onBlock: (ByteArray) -> Unit) {
        if (running.getAndSet(true)) return
        thread = Thread({
            var record: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, BLOCK_BYTES * 8),
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialized (state=${record.state})")
                    return@Thread
                }
                runCatching { NoiseSuppressor.create(record.audioSessionId) } // best effort
                recorder = record
                record.startRecording()
                Log.i(TAG, "capture started: ${SAMPLE_RATE}Hz mono int16, block=$BLOCK_SAMPLES samples")
                val buf = ShortArray(BLOCK_SAMPLES)
                while (running.get()) {
                    val n = record.read(buf, 0, BLOCK_SAMPLES)
                    if (n > 0) {
                        val bytes = ByteArray(n * 2)
                        var i = 0
                        for (s in 0 until n) {
                            bytes[i++] = (buf[s].toInt() and 0xFF).toByte()
                            bytes[i++] = ((buf[s].toInt() shr 8) and 0xFF).toByte()
                        }
                        onBlock(bytes)
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error $n; stopping capture")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture failed: ${t.message}", t)
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                recorder = null
                running.set(false)
                Log.i(TAG, "capture stopped")
            }
        }, "dshpm-mic").apply { isDaemon = true; start() }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(1500)
        thread = null
    }

    override fun close() {
        stop()
    }

    companion object {
        const val TAG = "DshPmVoice"
        const val SAMPLE_RATE = 16_000
        const val BLOCK_SAMPLES = ChunkCoalescer.BLOCK_SAMPLES
        const val BLOCK_BYTES = BLOCK_SAMPLES * 2
    }
}
