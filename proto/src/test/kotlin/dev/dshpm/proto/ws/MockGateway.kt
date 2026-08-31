package dev.dshpm.proto.ws

import dev.dshpm.proto.codec.FrameCodec
import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.ServerFrame
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * In-process mock voice gateway (Java-WebSocket server on an ephemeral port).
 * Behaviors are injected per test via [onClientFrame] / [onClientOpen].
 */
class MockGateway(
    private val onClientOpen: (MockGateway, WebSocket) -> Unit = { _, _ -> },
    private val onClientFrame: (MockGateway, WebSocket, ClientFrame, String) -> Unit = { _, _, _, _ -> },
) : WebSocketServer(ephemeralLoopback()) {

    val started = CountDownLatch(1)
    val connectionsSeen = CopyOnWriteArrayList<WebSocket>()
    val rawInbound = CopyOnWriteArrayList<String>()

    /** Monotonic open count — unlike [connectionsSeen] it never decreases on close. */
    val totalConnections = java.util.concurrent.atomic.AtomicInteger()

    fun startAndAwait() {
        start()
        check(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "mock gateway failed to start" }
    }

    /** Explicit pre-picked port: WebSocketServer.getAddress() reports the constructor
     *  address, so binding port 0 would leave address.port == 0 forever. */
    val listenPort: Int get() = address.port

    companion object {
        private fun ephemeralLoopback(): InetSocketAddress =
            java.net.ServerSocket(0).use { s -> InetSocketAddress("127.0.0.1", s.localPort) }
    }

    fun send(conn: WebSocket, frame: ServerFrame) = conn.send(FrameCodec.encode(frame))

    fun sendRaw(conn: WebSocket, raw: String) = conn.send(raw)

    override fun onStart() {
        started.countDown()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        totalConnections.incrementAndGet()
        connectionsSeen.add(conn)
        onClientOpen(this, conn)
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        message ?: return
        rawInbound.add(message)
        when (val frame = FrameCodec.decode(message)) {
            is ClientFrame -> onClientFrame(this, conn, frame, message)
            else -> Unit
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer?) {
        // media up — not exercised by these tests
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connectionsSeen.remove(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        // socket errors during abrupt drops are expected in reconnect tests
    }
}
