package dev.dshpm.android

import dev.dshpm.android.voice.ChunkCoalescer
import dev.dshpm.android.voice.MicSource
import dev.dshpm.android.voice.PlayerSink
import dev.dshpm.android.voice.VoiceController
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.SessionEnded
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.VoiceGatewayClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * REAL-gateway voice-session integration (AND4-2 ⑥): session.start{observe:false}
 * → session.started → head.list.result → upstream silence block (800 samples,
 * tk BLOCK alignment) accepted without error → graceful session.end/ended.
 *
 * Empirical (probed live 2026-08-31): pure silence/noise upstream triggers NO
 * downlink — the head's VAD correctly stays silent — so downlink assertion is
 * observational (logged, not asserted). Real-speech downlink evidence is the
 * device acceptance (PTT walkthrough, logcat + screenshots).
 *
 * Disabled by default; enable with DASHPM_INTEGRATION=1 or -Pintegration=true.
 */
class VoiceSessionIntegrationTest {

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

    @Test(timeout = 30_000L)
    fun realGatewayVoiceSessionLifecycle() {
        assumeTrue("integration disabled — enable with DASHPM_INTEGRATION=1 or -Pintegration=true", integrationEnabled())
        val token = loadToken()
        assumeTrue("VOICE_GATEWAY_TOKEN not found in ~/.config/voice-gateway/env", token != null)

        val sent = LinkedBlockingQueue<dev.dshpm.proto.frame.ClientFrame>()
        val audioUp = LinkedBlockingQueue<ByteArray>()
        val errors = CopyOnWriteArrayList<ErrorFrame>()
        val downlinkAudio = CopyOnWriteArrayList<ByteArray>()

        val player = object : PlayerSink {
            override fun enqueue(pcm: ByteArray) { downlinkAudio.add(pcm) }
            override fun dropAll() {}
            override fun close() {}
        }
        val mic = object : MicSource {
            override fun start(onBlock: (ByteArray) -> Unit) {}
            override fun stop() {}
            override fun close() {}
        }

        val controller = VoiceController(
            sendPort = { f -> sent.put(f) },
            audioPort = { pcm -> audioUp.put(pcm) },
            mic = mic,
            player = player,
        )
        // record errors + audio via a wrapper listener
        val client = VoiceGatewayClient(
            uri = URI("ws://127.0.0.1:8765/ws"),
            token = token!!,
            listener = object : dev.dshpm.proto.ws.GatewayListener {
                override fun onState(state: ConnectionState) { controller.onState(state) }
                override fun onFrame(frame: dev.dshpm.proto.frame.WsFrame) {
                    if (frame is ErrorFrame) errors.add(frame)
                    controller.onFrame(frame)
                }
                override fun onMedia(pcm: ByteArray) { controller.onMedia(pcm) }
                override fun onError(t: Throwable) { controller.onError(t) }
            },
            config = VoiceGatewayClient.Config(pingIntervalMillis = 60_000),
        )
        controller.rebindSendPort { f -> sent.put(f); client.send(f) }
        controller.rebindAudioPort { pcm -> audioUp.put(pcm); client.sendAudio(pcm) }

        try {
            client.connect()
            await("CONNECTED") { controller.state.connection == ConnectionState.CONNECTED }
            controller.wantsSession = true
            controller.activate()
            await("session.started → LIVE") { controller.state.session == VoiceController.SessionState.LIVE }
            assertTrue("session_id missing", controller.state.sessionId != null)
            await("head.list.result") { controller.state.heads.isNotEmpty() }
            assertTrue("expected an active head, got ${controller.state.activeHead}",
                controller.state.activeHead != null)

            // one 200 ms silence send (4 × 800-sample blocks, tk alignment)
            repeat(4) { controller.onMicBlock(ByteArray(ChunkCoalescer.BLOCK_SAMPLES * 2)) }
            await("upstream binary accepted") { audioUp.isNotEmpty() }
            assertEquals(1, audioUp.size)
            assertEquals(ChunkCoalescer.SEND_BYTES, audioUp.peek().size)

            // silence upstream: no error frames may arrive (audio-before-session
            // would error no_session; post-session silence is simply ignored by VAD)
            Thread.sleep(3_000)
            assertTrue("unexpected error frames: $errors", errors.isEmpty())

            // observational: silence yields no downlink (live-probed); log it
            println("downlink audio chunks observed: ${downlinkAudio.size}")

            controller.deactivate()
            await("session.ended") { controller.state.session == VoiceController.SessionState.ENDED }
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
