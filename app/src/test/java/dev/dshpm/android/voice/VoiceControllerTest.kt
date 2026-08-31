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

    /** AND4-5: records tail scheduling; blocks emit only on explicit flush(). */
    private class FakeTail : VoiceController.TailScheduler {
        var calls = 0; var lastCount = 0; var lastPace = 0L
        private var emit: (() -> Unit)? = null
        private var remaining = 0
        override fun schedule(count: Int, paceMs: Long, emit: () -> Unit) {
            calls++; lastCount = count; lastPace = paceMs
            this.emit = emit; remaining = count
        }
        fun flush(n: Int = remaining) {
            val e = emit ?: return
            repeat(minOf(n, remaining)) { remaining--; e() }
        }
    }

    private class Env {
        val sent = mutableListOf<ClientFrame>()
        val audio = mutableListOf<ByteArray>()
        val mic = FakeMic()
        val player = FakePlayer()
        val tail = FakeTail()
        val ctrl = VoiceController(
            sendPort = { sent.add(it) },
            audioPort = { audio.add(it) },
            mic = mic,
            player = player,
            nowMs = { 1_000L },
            tailScheduler = tail,
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
    fun downlinkCountersCountArrivalsEvenWhileMuted() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.onFrame(HeadTurn(phase = "user_start")) // muted
        e.ctrl.onMedia(ByteArray(100))
        e.ctrl.onMedia(ByteArray(50))
        assertEquals(2, e.ctrl.state.downlinkFrames) // ②c: arrivals counted pre-gate
        assertEquals(150, e.ctrl.state.downlinkBytes)
        assertEquals(2, e.ctrl.state.downlinkMuteDropped)
        assertEquals(0, e.player.queued.size) // ...but nothing enqueued while muted
        e.ctrl.onFrame(HeadTurn(phase = "assistant_start"))
        e.ctrl.onMedia(ByteArray(70))
        assertEquals(3, e.ctrl.state.downlinkFrames)
        assertEquals(2, e.ctrl.state.downlinkMuteDropped) // unchanged by the unmuted frame
        assertEquals(1, e.player.queued.size)
    }

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

    // -------------------------------------------- AND4-4 服务端驱动让位

    /** During a hold: head.turn(user_end) must yield — stop mic, discard the
     *  stale coalescer tail, reset PTT, note the transcript, open playback. */
    @Test
    fun userEndDuringHoldYieldsCapture() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(2) { e.micBlock() } // partial block pending in the coalescer
        e.ctrl.onFrame(HeadTurn(phase = "user_end"))
        assertEquals(1, e.mic.stops) // capture ended by the server flip
        assertEquals(VoiceController.PttState.IDLE, e.ctrl.state.ptt) // button visual reset
        assertFalse(e.ctrl.state.muted) // mute window opened
        assertEquals(0, e.audio.size) // 停合块: stale partial discarded, not flushed
        assertEquals(0, e.tail.calls) // AND4-5: yield stop sends NO silence tail
        val yield = e.ctrl.state.transcript.filter { it.phase == VoiceController.YIELD_PHASE }
        assertEquals(1, yield.size)
        assertTrue(yield[0].detail!!.contains("自动让位"))
        e.ctrl.onMedia(ByteArray(64)) // reply now passes
        assertEquals(1, e.player.queued.size)
        assertEquals(0, e.ctrl.state.downlinkMuteDropped)
    }

    /** During a hold: head.turn(assistant_start) also yields. */
    @Test
    fun assistantStartDuringHoldYieldsCapture() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        e.ctrl.onFrame(HeadTurn(phase = "assistant_start"))
        assertEquals(1, e.mic.stops)
        assertEquals(VoiceController.PttState.IDLE, e.ctrl.state.ptt)
        assertFalse(e.ctrl.state.muted)
        assertEquals(1, e.ctrl.state.transcript.count { it.phase == VoiceController.YIELD_PHASE })
    }

    /** During a hold (even muted by an earlier interrupted echo): the FIRST
     *  downlink binary yields AND itself passes through to the player. */
    @Test
    fun firstBinaryDuringHoldYieldsAndPasses() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        e.ctrl.onFrame(HeadTurn(phase = "interrupted")) // mute window re-asserted mid-hold
        assertTrue(e.ctrl.state.muted)
        e.ctrl.onMedia(ByteArray(64))
        assertEquals(1, e.mic.stops) // yielded by the binary itself
        assertEquals(VoiceController.PttState.IDLE, e.ctrl.state.ptt)
        assertFalse(e.ctrl.state.muted)
        assertEquals(1, e.player.queued.size) // the trigger frame passed
        assertEquals(0, e.ctrl.state.downlinkMuteDropped)
        assertEquals(1, e.ctrl.state.transcript.count { it.phase == VoiceController.YIELD_PHASE })
    }

    /** Ordering A — head trigger first: subsequent binaries just play; the
     *  binary trigger must not double-yield. */
    @Test
    fun headYieldThenBinaryDoesNotDoubleYield() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        e.ctrl.onFrame(HeadTurn(phase = "user_end")) // yield #1
        e.ctrl.onMedia(ByteArray(10))
        e.ctrl.onMedia(ByteArray(10))
        assertEquals(1, e.mic.stops)
        assertEquals(2, e.player.queued.size)
        assertEquals(1, e.ctrl.state.transcript.count { it.phase == VoiceController.YIELD_PHASE })
        assertEquals(0, e.ctrl.state.downlinkMuteDropped)
    }

    /** Ordering B — binary first: a later head.turn(user_end|assistant_start)
     *  must not fire a second yield (autoYield guards on CAPTURING). */
    @Test
    fun binaryYieldThenHeadTurnsDoNotDoubleYield() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        e.ctrl.onMedia(ByteArray(10)) // yield #1 (binary)
        e.ctrl.onFrame(HeadTurn(phase = "user_end"))
        e.ctrl.onFrame(HeadTurn(phase = "assistant_start"))
        assertEquals(1, e.mic.stops)
        assertEquals(1, e.ctrl.state.transcript.count { it.phase == VoiceController.YIELD_PHASE })
        // both head phases still land in the transcript normally
        assertTrue(e.ctrl.state.transcript.any { it.phase == "user_end" })
        assertTrue(e.ctrl.state.transcript.any { it.phase == "assistant_start" })
        assertEquals(1, e.player.queued.size)
    }

    /** The finger's late release after a yield is an idempotent no-op. */
    @Test
    fun lateReleaseAfterYieldIsNoOp() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(2) { e.micBlock() }
        e.ctrl.onFrame(HeadTurn(phase = "user_end")) // server-driven yield
        e.ctrl.pttUp() // the finger finally comes off — much later
        assertEquals(1, e.mic.stops) // NOT stopped a second time
        assertEquals(0, e.audio.size) // reset coalescer had nothing to flush
        assertEquals(0, e.tail.calls) // AND4-5: yield stop sends NO silence tail
        assertEquals(VoiceController.PttState.IDLE, e.ctrl.state.ptt)
    }

    /** A plain hold with no server signal must NOT auto-release. */
    @Test
    fun plainHoldWithoutServerSignalDoesNotYield() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(9) { e.micBlock() } // 450 ms of speech, server stays silent
        assertEquals(VoiceController.PttState.CAPTURING, e.ctrl.state.ptt)
        assertEquals(0, e.mic.stops)
        assertEquals(0, e.ctrl.state.transcript.count { it.phase == VoiceController.YIELD_PHASE })
        assertEquals(2, e.audio.size) // capture still flows upstream (9 blocks = 2 sends + remainder)
        e.ctrl.pttUp() // and manual release still works normally
        assertEquals(1, e.mic.stops)
        assertEquals(3, e.audio.size) // remainder flushed
        assertEquals(1, e.tail.calls) // AND4-5: release schedules the silence tail
    }

    /** After a yield the button is reusable: a new press starts a fresh round. */
    @Test
    fun pttDownAfterYieldStartsFreshCapture() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        e.ctrl.onFrame(HeadTurn(phase = "user_end")) // yield
        e.ctrl.pttUp() // late release no-op
        e.ctrl.pttDown() // user presses again
        assertEquals(2, e.mic.starts)
        assertEquals(VoiceController.PttState.CAPTURING, e.ctrl.state.ptt)
        e.ctrl.onFrame(HeadTurn(phase = "user_end")) // and can yield again
        assertEquals(2, e.mic.stops)
        assertEquals(2, e.ctrl.state.transcript.count { it.phase == VoiceController.YIELD_PHASE })
    }

    // -------------------------------------------- AND4-5 松开静音尾

    /** User release appends 3 × 200 ms all-zero blocks (600 ms) through the
     *  existing send path, paced at 200 ms per block, after the flush. */
    @Test
    fun releaseAppendsPacedZeroTail() {
        val e = Env()
        e.connectAndStartSession()
        val before = e.ctrl.state.upstreamBlocks
        e.ctrl.pttDown()
        repeat(2) { e.micBlock() } // real speech…
        e.ctrl.pttUp()             // …then release
        assertEquals(1, e.tail.calls) // exactly one tail schedule per release
        assertEquals(VoiceController.TAIL_BLOCKS, e.tail.lastCount)
        assertEquals(VoiceController.TAIL_PACE_MS, e.tail.lastPace) // 200 ms pacing request
        assertEquals(1, e.audio.size) // flush first — tail not yet flowing
        e.tail.flush()
        assertEquals(VoiceController.TAIL_BLOCKS, e.audio.size - 1) // full tail flowed
        val tailBlocks = e.audio.drop(1)
        tailBlocks.forEachIndexed { i, b ->
            assertEquals(ChunkCoalescer.SEND_BYTES, b.size) // full 200 ms block each
            assertTrue("tail block $i must be all-zero", b.all { it == 0.toByte() })
        }
        assertEquals(before + 1, e.ctrl.state.upstreamBlocks - VoiceController.TAIL_BLOCKS)
    }

    /** Server-driven yield stop sends NO tail — the turn already flipped. */
    @Test
    fun yieldStopSendsNoTailEvenAcrossAssistantPhases() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(4) { e.micBlock() }
        e.ctrl.onFrame(HeadTurn(phase = "user_end")) // yield (no tail)
        e.ctrl.onFrame(HeadTurn(phase = "assistant_start"))
        e.ctrl.onFrame(HeadTurn(phase = "assistant_end"))
        assertEquals(0, e.tail.calls)
        e.ctrl.pttUp() // late release — no-op, still no tail
        assertEquals(0, e.tail.calls)
    }

    /** A new press before the tail finishes suppresses the remaining zero
     *  blocks — zeros must not interleave with the fresh utterance. */
    @Test
    fun repressDuringTailSuppressesRemainingZeroBlocks() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(2) { e.micBlock() }
        e.ctrl.pttUp()
        e.tail.flush(1) // first zero block flows…
        val afterOne = e.audio.size
        assertEquals(2, afterOne) // flush + 1 tail block
        e.ctrl.pttDown() // …user presses again mid-tail
        repeat(1) { e.micBlock() } // fresh speech enters the coalescer
        e.tail.flush() // remaining blocks must be suppressed
        assertEquals(afterOne, e.audio.size) // NO further sends during capture
        // and the fresh round's own release schedules its own tail
        e.ctrl.pttUp()
        assertEquals(2, e.tail.calls)
        e.tail.flush()
        assertEquals(afterOne + 1 + VoiceController.TAIL_BLOCKS, e.audio.size) // flush + own tail
    }

    /** Deactivate during a hold routes through pttUp: tail schedules (harmless
     *  on session end) but blocks stop with the scheduler's suppression guard. */
    @Test
    fun deactivateDuringHoldSchedulesTailOnce() {
        val e = Env()
        e.connectAndStartSession()
        e.ctrl.pttDown()
        repeat(2) { e.micBlock() }
        e.ctrl.deactivate()
        assertEquals(1, e.tail.calls)
    }
}
