package dev.dshpm.proto

import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.Ping
import dev.dshpm.proto.frame.Pong
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.VoiceGatewayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * REAL-gateway integration (voice-gateway.service on 0.0.0.0:8765).
 *
 * Disabled by default; enable with ONE of:
 *   DASHPM_INTEGRATION=1 ./gradlew :proto:test
 *   ./gradlew :proto:test -Pintegration=true
 *
 * Token comes from ~/.config/voice-gateway/env (VOICE_GATEWAY_TOKEN).
 */
class GatewayIntegrationTest {

    private fun integrationEnabled(): Boolean =
        System.getProperty("dshpm.integration") == "true" ||
            System.getenv("DASHPM_INTEGRATION") == "1"

    private fun loadToken(): String? {
        val home = System.getenv("HOME") ?: return null
        val envFile = Path.of(home, ".config", "voice-gateway", "env")
        if (!Files.isRegularFile(envFile)) return null
        return Files.readAllLines(envFile)
            .firstOrNull { it.startsWith("VOICE_GATEWAY_TOKEN=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    @Test(timeout = 20_000L)
    @Integration
    fun realGatewayAuthOkDeclaresV1AndAnswersPing() {
        assumeTrue("integration disabled — enable with DASHPM_INTEGRATION=1 or -Pintegration=true", integrationEnabled())
        val token = loadToken()
        assumeTrue("VOICE_GATEWAY_TOKEN not found in ~/.config/voice-gateway/env", token != null)

        val listener = RecordingListener()
        val client = VoiceGatewayClient(
            uri = URI("ws://127.0.0.1:8765/ws"),
            token = token!!,
            listener = listener,
            config = VoiceGatewayClient.Config(pingIntervalMillis = 60_000),
        )
        try {
            client.connect()
            assertTrue("never reached CONNECTED (states=${listener.states.toList()})", listener.awaitState(10, ConnectionState.CONNECTED))

            val authOk = listener.awaitFrameClass(10, AuthOk::class.java) as AuthOk
            assertNotNull(authOk.sessionId)
            assertTrue("session_id should look like s-<8hex>, was ${authOk.sessionId}", authOk.sessionId.startsWith("s-"))
            // WSP-001 freeze marker: the live gateway must self-declare proto v1.
            assertEquals("v1", authOk.proto)

            client.send(Ping)
            val pong = listener.awaitFrameClass(10, Pong::class.java)
            assertTrue("no pong for app-level ping", pong is Pong && pong.ts > 0)
        } finally {
            client.close()
        }
    }
}
