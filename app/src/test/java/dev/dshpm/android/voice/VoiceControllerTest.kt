package dev.dshpm.android.voice

import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.HeadListResult
import dev.dshpm.proto.frame.HeadSwitchResult
import dev.dshpm.proto.frame.HeadTurn
import dev.dshpm.proto.frame.SessionEnd
import dev.dshpm.proto.frame.SessionStart
import dev.dshpm.proto.frame.SessionStarted
import dev.dshpm.proto.ws.ConnectionState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice-page controller: session lifecycle, PTT gate, barge-in sentinel. */
class VoiceControllerTest {

    private class FakeMic : MicSource {
        var starts = 0; var stops = 0
        var cb: ((ByteArray) -> Unit)? = null
        override fun start(onBlock: (ByteArray) -> Unit) { starts++; cb = onBlock }
        override fun stop() { stops++ }
        override fun close() {}
    }

    private class FakePlayer : PlayerSink {
        val queued = mutableListOf<ByteArray>()
        var drops = 0
        override fun enqueue(pcm: ByteArray) { queued.add(pcm) }
        override fun dropAll() { drops++ }
        override fun close() {}
    }

    private class Env {
        val sent = mutableListOf<ClientFrame>()
        val audio = mutableListOf<ByteArray>()
        val mic = FakeMic()
        val player = FakePlayer()
        val ctrl = VoiceController(
            sendPort = { sent.add(it) },
            audioPort = { audio.add(it) },
            mic = mic,
            player = player,
            nowMs = { 1_000L },
        )

        fun connectAndStartSession() {
            ctrl.onState(ConnectionState.CONNECTED)
            ctrl.activate()
            ctrl.onFrame(SessionStarted(sessionId = "s-1", reseeded = false, entries = 0, observe = false, topics = emptyList()))
        }

        fun micBlock(n: Int = ChunkCoalescer.BLOCK_SAMPLES) = ctrl.onMicBlock(ByteArray(n * 2))
    }

    // ------------------------------------------------------------- session

    @Test
    fun activateSendsNonObserveSessionStartAndHeadList() {
        val e = Env()
        e.ctrl.onState(ConnectionState.CONNECTED)
        e.ctrl.activate()
        val start = e.sent.filterIsInstance<SessionStart>().single()
        assertFalse(start.observe!!)
        assertTrue(e.sent.filterIsInstance<HeadListResult>().isEmpty()) // only request sent
        assertTrue(e.sent.any { it is dev.dshpm.proto.frame.HeadList })
    }

    @Test
    fun activateWhileDisconnectedShowsErrorAndSendsNothing() {
        val e = Env()
        e.ctrl.activate()
        assertTrue(e.sent.isEmpty())
        assertTrue(e.ctrl.state.error!!.contains("未连接"))
    }

    @Test
    fun sessionStartedGoesLiveAndDeactivateEndsGracefully() {
        val e = Env()
        e.connectAndStartSession()
        assertEquals(VoiceController.SessionState.LIVE, e.ctrl.state.session)
        assertEquals("s-1", e.ctrl.state.sessionId)
        e.ctrl.deactivate()
        assertTrue(e.sent.any { it is SessionEnd })
        assertEquals(VoiceController.SessionState.ENDED, e.ctrl.state.session)
    }

    // ----------------------------------------------------------------- PTT

    @Test
    fun pttDownGatesOnLiveSessionAndCapturesUntilRelease() {
        val e = Env()
        e.ctrl.pttDown() // not LIVE yet
        assertEquals(0, e.mic.starts)
        assertTrue(e.ctrl.state.error!!.contains("会话未建立"))

        e.connectAndStartSession()
        e.ctrl.pttDown()
        assertEquals(1, e.mic.starts)
        assertEquals(VoiceController.PttState.CAPTURING, e.ctrl.state.ptt)

        repeat(4) { e.micBlock() } // 200 ms worth
        e.ctrl.pttUp()
        assertEquals(1, e.mic.stops)
        assertEquals(1, e.audio.size) // one coalesced send
        assertEquals(ChunkCoalescer.SEND_BYTES, e.audio[0].size)
        assertEquals(VoiceController.PttState.IDLE, e.ctrl.state.ptt)
    }

    @Test
    fun pttReleaseFlushesPartialBlock() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(2) { e.micBlock() } // 100 ms
        e.ctrl.pttUp()
        assertEquals(1, e.audio.size)
        assertEquals(2 * ChunkCoalescer.BLOCK_SAMPLES * 2, e.audio[0].size)
    }

    // ------------------------------------------------------- barge-in 状态机

    @Test
    fun userStartInterruptsDropsPlaybackAndMutesDownlink() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.onFrame(HeadTurn(phase = "assistant_start")) // unmuted baseline
        e.ctrl.onMedia(byteArrayOf(1)) // queued
        assertEquals(1, e.player.queued.size)

        e.ctrl.onFrame(HeadTurn(phase = "user_start")) // barge-in
        assertEquals(1, e.player.drops)
        assertTrue(e.ctrl.state.muted)
        e.ctrl.onMedia(byteArrayOf(2)) // stale assistant audio — dropped
        assertEquals(1, e.player.queued.size)

        e.ctrl.onFrame(HeadTurn(phase = "assistant_start"))
        assertFalse(e.ctrl.state.muted)
        e.ctrl.onMedia(byteArrayOf(3))
        assertEquals(2, e.player.queued.size)
    }

    @Test
    fun interruptedPhaseAlsoDrops() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.onFrame(HeadTurn(phase = "interrupted"))
        assertEquals(1, e.player.drops)
        assertTrue(e.ctrl.state.muted)
    }

    @Test
    fun transcriptRecordsPhasesAndDetails() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.onFrame(HeadTurn(phase = "user_text", detail = "你好"))
        e.ctrl.onFrame(HeadTurn(phase = "assistant_end"))
        assertEquals(listOf("user_text", "assistant_end"), e.ctrl.state.transcript.map { it.phase })
        assertEquals("你好", e.ctrl.state.transcript[0].detail)
    }

    // ---------------------------------------------------------------- heads

    @Test
    fun headListResultParsesProfilesAndSwitchUpdatesActive() {
        val e = Env()
        e.connectAndStartSession()
        val profiles = listOf(
            buildJsonObject { put("name", "nova"); put("label", "Nova·任务头") },
            buildJsonObject { put("name", "glm") }, // label falls back to name
        )
        e.ctrl.onFrame(HeadListResult(reqId = "hl-1", active = "nova", fileBacked = true, envPinned = false, profiles = profiles))
        assertEquals(2, e.ctrl.state.heads.size)
        assertEquals("nova", e.ctrl.state.activeHead)
        assertEquals("Nova·任务头", e.ctrl.state.heads[0].label)
        assertEquals("glm", e.ctrl.state.heads[1].label)

        e.ctrl.switchHead("glm")
        e.ctrl.onFrame(HeadSwitchResult(reqId = "hs-1", ok = true, active = "glm", note = "下一次语音连接生效"))
        assertEquals("glm", e.ctrl.state.activeHead)
        assertEquals("下一次语音连接生效", e.ctrl.state.switchNote)
    }

    @Test
    fun failedSwitchKeepsActiveHead() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.onFrame(HeadListResult(reqId = "hl-1", active = "nova", fileBacked = true, envPinned = false,
            profiles = listOf(buildJsonObject { put("name", "nova") })))
        e.ctrl.switchHead("glm")
        e.ctrl.onFrame(HeadSwitchResult(reqId = "hs-1", ok = false, active = "nova", note = "unknown head"))
        assertEquals("nova", e.ctrl.state.activeHead)
    }
}
