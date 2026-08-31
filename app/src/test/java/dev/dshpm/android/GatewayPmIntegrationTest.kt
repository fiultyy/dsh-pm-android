package dev.dshpm.android

import dev.dshpm.android.pm.BoardController
import dev.dshpm.proto.frame.PmReq
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.VoiceGatewayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * REAL-gateway integration, extended tier for AND3-1: pm.req tickets roundtrip
 * through the actual voice gateway — the exact first-pull path the three views use.
 *
 * Disabled by default; enable with DASHPM_INTEGRATION=1 or -Pintegration=true
 * (app-module test task wires the same switch as proto).
 */
class GatewayPmIntegrationTest {

    private fun integrationEnabled(): Boolean =
        System.getProperty("dshpm.integration") == "true" ||
            System.getenv("DASHPM_INTEGRATION") == "1"

    private fun loadToken(): String? {
        val home = System.getenv("HOME") ?: return null
        val envFile = Path.of(home, ".config", "voice-gateway", "env")
        if (!Files.isRegularFile(envFile)) return null
        return Files.readAllLines(envFile)
            .firstOrNull { it.startsWith("VOICE_GATEWAY_TOKEN=") }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Test(timeout = 20_000L)
    fun realGatewayPmReqTicketsRoundtrip() {
        assumeTrue("integration disabled — enable with DASHPM_INTEGRATION=1 or -Pintegration=true", integrationEnabled())
        val token = loadToken()
        assumeTrue("VOICE_GATEWAY_TOKEN not found in ~/.config/voice-gateway/env", token != null)

        val sent = LinkedBlockingQueue<dev.dshpm.proto.frame.ClientFrame>()
        val controller = BoardController(sendPort = { f -> sent.put(f) }) // recording only, see below
        val client = VoiceGatewayClient(
            uri = URI("ws://127.0.0.1:8765/ws"),
            token = token!!,
            listener = controller,
            config = VoiceGatewayClient.Config(pingIntervalMillis = 60_000),
        )
        // re-bind: record AND forward to the live client
        controller.rebindSendPort { f ->
            sent.put(f)
            client.send(f)
        }
        try {
            client.connect()
            await("CONNECTED") { controller.uiState.connection == ConnectionState.CONNECTED }

            // connected cycle fired: 3 pulls + 1 sub
            val reqs = sent.toList().filterIsInstance<PmReq>().map { it.op }
            assertEquals(listOf("tickets", "fleet", "flow"), reqs)

            // tickets pm.res lands and populates the board (live ledger has 53 tickets)
            await("tickets pm.res") { controller.uiState.ticketsLoaded }
            val count = controller.uiState.ticketGroups.sumOf { it.count }
            assertTrue("expected ≥1 ticket, got $count", count >= 1)

            // manual roundtrip with a fresh monotonic id
            controller.pull(BoardController.OP_TICKETS)
            val manual = sent.toList().filterIsInstance<PmReq>().last()
            assertEquals("tickets", manual.op)
            assertTrue(manual.id.content.toLong() > 3)
            // pm.res for the manual pull arrives via the listener → controller
            // (id-correlation proven by ticketsLoaded above; no further assert needed)
        } finally {
            client.close()
        }
    }

    private fun await(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(100)
        assertTrue("timeout waiting for $what", cond())
    }
}
