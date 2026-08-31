package dev.dshpm.proto

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Marks a test that hits the REAL voice gateway (ws://127.0.0.1:8765/ws).
 * Skipped by default; enable with DASHPM_INTEGRATION=1 or -Pintegration=true.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Integration

/** Await helper. */
fun CountDownLatch.awaitSeconds(seconds: Long): Boolean = await(seconds, TimeUnit.SECONDS)

/** Collecting listener used across WS tests. */
class RecordingListener : dev.dshpm.proto.ws.GatewayListener {
    val states = LinkedBlockingQueue<dev.dshpm.proto.ws.ConnectionState>()
    val frames = LinkedBlockingQueue<dev.dshpm.proto.frame.WsFrame>()
    val media = LinkedBlockingQueue<ByteArray>()
    val errors = LinkedBlockingQueue<Throwable>()

    /** Waits for the given state; returns true when observed within [seconds]. */
    fun awaitState(seconds: Long, vararg wanted: dev.dshpm.proto.ws.ConnectionState): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            val s = states.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (s in wanted) return true
        }
        return false
    }

    fun awaitFrameClass(seconds: Long, clazz: Class<out dev.dshpm.proto.frame.WsFrame>): dev.dshpm.proto.frame.WsFrame? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            val f = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (clazz.isInstance(f)) return f
        }
        return null
    }

    override fun onState(state: dev.dshpm.proto.ws.ConnectionState) {
        states.put(state)
    }

    override fun onFrame(frame: dev.dshpm.proto.frame.WsFrame) {
        frames.put(frame)
    }

    override fun onMedia(pcm: ByteArray) {
        media.put(pcm)
    }

    override fun onError(error: Throwable) {
        errors.put(error)
    }
}
