package dev.dshpm.proto.ws

import dev.dshpm.proto.RecordingListener
import dev.dshpm.proto.frame.Auth
import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.Ping
import dev.dshpm.proto.frame.Pong
import dev.dshpm.proto.frame.UnknownFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

/** Auth handshake + ping/pong + tolerance against an in-process mock gateway. */
class MockGatewayHandshakeTest {

    private lateinit var gateway: MockGateway
    private lateinit var client: VoiceGatewayClient

    private fun startGateway(
        onOpen: (MockGateway, org.java_websocket.WebSocket) -> Unit = { _, _ -> },
        onFrame: (MockGateway, org.java_websocket.WebSocket, dev.dshpm.proto.frame.ClientFrame, String) -> Unit = { _, _, _, _ -> },
    ): MockGateway = MockGateway(onOpen, onFrame).also { it.startAndAwait() }

    private fun defaultClient(token: String, listener: RecordingListener, port: Int): VoiceGatewayClient =
        VoiceGatewayClient(
            uri = URI("ws://127.0.0.1:$port/ws"),
            token = token,
            listener = listener,
            config = VoiceGatewayClient.Config(pingIntervalMillis = 60_000),
        )

    @After
    fun tearDown() {
        if (::client.isInitialized) client.close()
        if (::gateway.isInitialized) gateway.stop(1000)
    }

    @Test
    fun authHandshakeReachesConnectedAndCarriesProtoV1() {
        val listener = RecordingListener()
        gateway = startGateway(onFrame = { gw, conn, frame, _ ->
            when (frame) {
                is Auth -> gw.send(conn, AuthOk(sessionId = "s-mock0001", proto = "v1"))
                is Ping -> gw.send(conn, Pong(ts = 1788135286.5))
                else -> Unit
            }
        })
        client = defaultClient("tok-1", listener, gateway.listenPort)

        client.connect()

        assertTrue("never reached CONNECTED", listener.awaitState(5, ConnectionState.CONNECTED))
        val authOk = listener.awaitFrameClass(5, AuthOk::class.java) as AuthOk
        assertEquals("s-mock0001", authOk.sessionId)
        assertEquals("v1", authOk.proto)
        assertEquals("s-mock0001", client.sessionId)

        // app-level ping → pong roundtrip
        client.send(Ping)
        val pong = listener.awaitFrameClass(5, Pong::class.java)
        assertEquals(Pong(ts = 1788135286.5), pong)
    }

    @Test
    fun unknownFrameFromServerIsToleratedWithoutDisconnect() {
        val listener = RecordingListener()
        gateway = startGateway(onFrame = { gw, conn, frame, _ ->
            when (frame) {
                is Auth -> {
                    gw.send(conn, AuthOk(sessionId = "s-mock0002", proto = "v1"))
                    // spec §0: server-side style future frame + malformed junk — client must survive both
                    gw.sendRaw(conn, """{"t":"v2.something","payload":{"deep":[1,2]}}""")
                    gw.sendRaw(conn, """{"t": """)
                }
                is Ping -> gw.send(conn, Pong(ts = 1788135286.5))
                else -> Unit
            }
        })
        client = defaultClient("tok-2", listener, gateway.listenPort)

        client.connect()

        assertTrue(listener.awaitState(5, ConnectionState.CONNECTED))
        val unknown = listener.awaitFrameClass(5, UnknownFrame::class.java) as UnknownFrame
        assertEquals("v2.something", unknown.t)
        val malformed = listener.awaitFrameClass(5, dev.dshpm.proto.frame.MalformedFrame::class.java)
        assertTrue(malformed is dev.dshpm.proto.frame.MalformedFrame)

        // socket must still be alive and usable
        client.send(Ping)
        val pong = listener.awaitFrameClass(5, Pong::class.java)
        assertTrue(pong is Pong)
        assertEquals(ConnectionState.CONNECTED, client.state)
    }

    @Test
    fun fatalAuthRejectionIsTerminalNoReconnectLoop() {
        val listener = RecordingListener()
        gateway = startGateway(onFrame = { gw, conn, frame, _ ->
            if (frame is Auth) {
                gw.send(conn, ErrorFrame(code = "auth", msg = "bad token", message = "bad token"))
                conn.close(1008, "auth")
            }
        })
        client = defaultClient("tok-bad", listener, gateway.listenPort)

        client.connect()

        assertTrue(
            "never reached CLOSED",
            listener.awaitState(5, ConnectionState.CLOSED),
        )
        // single connection ever — the client must not retry a rejected token
        TimeUnit.MILLISECONDS.sleep(600)
        assertEquals(1, gateway.totalConnections.get())
        assertEquals(ConnectionState.CLOSED, client.state)
    }
}
