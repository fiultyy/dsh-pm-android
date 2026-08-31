package dev.dshpm.proto.ws

import dev.dshpm.proto.RecordingListener
import dev.dshpm.proto.frame.Auth
import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.PmEvent
import dev.dshpm.proto.frame.SessionStart
import dev.dshpm.proto.frame.SessionStarted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TK-001 reconnect semantics: drop → backoff reconnect → re-auth →
 * `session.start{session_id}` resume → snapshot replay reconciled through the
 * `(source,msgid)` dedup window (PM-007): every event delivered exactly once.
 */
class ReconnectReplayTest {

    private lateinit var gateway: MockGateway
    private lateinit var client: VoiceGatewayClient

    @After
    fun tearDown() {
        if (::client.isInitialized) client.close()
        if (::gateway.isInitialized) gateway.stop(1000)
    }

    @Test
    fun dropReconnectsResumesSessionAndDedupsReplay() {
        val resumeSeen = CopyOnWriteArrayList<String?>()
        val firstDropDone = CountDownLatch(1)
        val dropped = java.util.concurrent.atomic.AtomicBoolean(false)
        // abrupt drop must happen AFTER in-flight frames flushed, else the abort
        // discards queued writes and the client never sees them
        val dropScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mock-drop").apply { isDaemon = true }
        }

        gateway = MockGateway(
            onClientOpen = { _, _ -> },
            onClientFrame = { gw, conn, frame, _ ->
                when (frame) {
                    is Auth -> gw.send(conn, AuthOk(sessionId = "s-mock0042", proto = "v1"))
                    is SessionStart -> {
                        if (dropped.compareAndSet(false, true)) {
                            // first life: plain start
                            gw.send(
                                conn,
                                SessionStarted(
                                    sessionId = frame.sessionId ?: "conv-42",
                                    reseeded = false,
                                    entries = 0,
                                    observe = false,
                                    topics = listOf("pm.event"),
                                ),
                            )
                            // deliver one pm.event BEFORE the drop
                            conn.send(
                                """{"t":"pm.event","kind":"ledger.commit","msgid":"m0","source":"ledger","seq":1,"replay":false}""",
                            )
                            // then abruptly drop the connection AFTER the writes flushed
                            dropScheduler.schedule({
                                conn.closeConnection(1006, "simulated drop")
                                firstDropDone.countDown()
                            }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
                        } else {
                            // second life: this is the auto-resume session.start
                            resumeSeen.add(frame.sessionId)
                            gw.send(
                                conn,
                                SessionStarted(
                                    sessionId = frame.sessionId ?: "?",
                                    reseeded = true,
                                    entries = 3,
                                    observe = false,
                                    topics = listOf("pm.event"),
                                ),
                            )
                            // snapshot replay: m0 again (pre-drop duplicate), m1 twice, m2 once
                            conn.send("""{"t":"pm.event","kind":"ledger.commit","msgid":"m0","source":"ledger","seq":1,"replay":true}""")
                            conn.send("""{"t":"pm.event","kind":"ledger.commit","msgid":"m1","source":"ledger","seq":2,"replay":true}""")
                            conn.send("""{"t":"pm.event","kind":"ledger.commit","msgid":"m1","source":"ledger","seq":2,"replay":true}""")
                            conn.send("""{"t":"pm.event","kind":"flow.tick","msgid":"m2","source":"flow","seq":1,"replay":true}""")
                        }
                    }
                    else -> Unit
                }
            },
        ).also { it.startAndAwait() }

        val listener = RecordingListener()
        client = VoiceGatewayClient(
            uri = URI("ws://127.0.0.1:${gateway.listenPort}/ws"),
            token = "tok-1",
            listener = listener,
            config = VoiceGatewayClient.Config(
                pingIntervalMillis = 60_000,
                initialReconnectDelayMillis = 100,
                maxReconnectDelayMillis = 200,
                reconnectDelayFactor = 1.0,
            ),
        )

        client.connect()
        assertTrue("first connect failed", listener.awaitState(5, ConnectionState.CONNECTED))
        // start a session with an explicit resumable id
        client.send(SessionStart(sessionId = "conv-42"))

        // wait for the forced drop + reconnect
        assertTrue(firstDropDone.await(5, TimeUnit.SECONDS))
        assertTrue("reconnect failed", listener.awaitState(10, ConnectionState.CONNECTED))

        // resume must carry the previously started session_id (TK-001)
        val resumeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (resumeSeen.isEmpty() && System.nanoTime() < resumeDeadline) TimeUnit.MILLISECONDS.sleep(50)
        assertEquals(listOf("conv-42"), resumeSeen.toList())

        // collect delivered pm.events (dedup window suppresses replayed duplicates)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val delivered = mutableListOf<String>()
        while (System.nanoTime() < deadline && delivered.size < 3) {
            val f = listener.frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (f is PmEvent && f.msgid != null) delivered.add(f.msgid!!)
        }
        assertEquals(listOf("m0", "m1", "m2"), delivered) // exactly once each, in order
        assertEquals(2L, client.suppressedReplays)         // m0 replay + m1 duplicate
        dropScheduler.shutdownNow()
    }
}
