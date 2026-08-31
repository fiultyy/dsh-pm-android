package dev.dshpm.android.voice

import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.EventFrame
import dev.dshpm.proto.frame.HeadList
import dev.dshpm.proto.frame.HeadListResult
import dev.dshpm.proto.frame.HeadSwitch
import dev.dshpm.proto.frame.HeadSwitchResult
import dev.dshpm.proto.frame.HeadTurn
import dev.dshpm.proto.frame.SessionEnded
import dev.dshpm.proto.frame.SessionStart
import dev.dshpm.proto.frame.SessionStarted
import dev.dshpm.proto.frame.SessionEnd
import dev.dshpm.proto.frame.WsFrame
import dev.dshpm.proto.ws.ConnectionState
import dev.dshpm.proto.ws.GatewayListener
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Voice-page controller (AND4-2): pure-JVM view-model over the v1 media plane.
 *
 * - Session lifecycle: voice tab activation sends `session.start{observe:false}`
 *   through the shared gateway client (AND2-3 resumer then owns reconnect
 *   reseed); tab exit sends graceful `session.end`.
 * - PTT (press-to-talk): local capture gate — press starts [MicSource], release
 *   stops it and flushes the coalescer remainder. The v1 wire has NO mic
 *   start/stop frames (audio simply flows while the session is live); PTT is
 *   purely a client-side capture switch, same as tk.
 * - Downlink: the receive path ONLY enqueues into [PlayerSink]; barge-in
 *   (`head.turn` phase `user_start`/`interrupted`) mutes + drops immediately
 *   (tk `_DROP_PLAYBACK` semantics); `assistant_start` re-opens playback.
 * - Heads: `head.list` renders the selection row; `head.switch` activates the
 *   single active head (takes effect on the next voice connection).
 *
 * Red lines: proto module untouched; all sends flow through the injected
 * [sendPort] (no direct network here).
 */
class VoiceController(
    private val sendPort: (ClientFrame) -> Unit,
    private val audioPort: (ByteArray) -> Unit,
    private val mic: MicSource,
    private val player: PlayerSink,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : GatewayListener {

    enum class SessionState { IDLE, STARTING, LIVE, ENDED }

    enum class PttState { IDLE, CAPTURING }

    data class TurnLine(val phase: String?, val detail: String?, val ts: Long)

    data class HeadProfile(val name: String, val label: String)

    data class VoiceState(
        val connection: ConnectionState = ConnectionState.IDLE,
        val session: SessionState = SessionState.IDLE,
        val sessionId: String? = null,
        val ptt: PttState = PttState.IDLE,
        val phase: String? = null,
        val muted: Boolean = false,
        val transcript: List<TurnLine> = emptyList(),
        val heads: List<HeadProfile> = emptyList(),
        val activeHead: String? = null,
        val switchNote: String? = null,
        val error: String? = null,
        val upstreamBlocks: Long = 0,
        /** AND4-3 ②c instrumentation: downlink binaries SEEN (before the mute gate). */
        val downlinkFrames: Long = 0,
        val downlinkBytes: Long = 0,
        /** Binaries dropped because we were inside a barge-in mute window. */
        val downlinkMuteDropped: Long = 0,
    )

    @Volatile
    var state: VoiceState = VoiceState()
        private set

    @Volatile
    var onChange: ((VoiceState) -> Unit)? = null

    /** Re-bind the control send port (tests attach a live client after construction). */
    @Volatile
    private var send: (ClientFrame) -> Unit = sendPort

    /** Re-bind the binary audio port likewise. */
    @Volatile
    private var audio: (ByteArray) -> Unit = audioPort

    fun rebindSendPort(port: (ClientFrame) -> Unit) {
        send = port
    }

    fun rebindAudioPort(port: (ByteArray) -> Unit) {
        audio = port
    }

    /** Tab wants the voice page: start (or re-start) the session when connected. */
    fun activate() {
        if (state.session == SessionState.LIVE || state.session == SessionState.STARTING) return
        if (state.connection != ConnectionState.CONNECTED) {
            update(state.copy(error = "网关未连接, 语音会话暂不可用"))
            return
        }
        update(state.copy(session = SessionState.STARTING, error = null))
        safeSend(SessionStart(observe = false))
        loadHeads()
    }

    /** Tab exit: graceful end (drops the resume slot server-side). */
    fun deactivate() {
        pttUp()
        player.dropAll()
        if (state.session == SessionState.LIVE || state.session == SessionState.STARTING) {
            safeSend(SessionEnd)
        }
        update(state.copy(session = SessionState.ENDED, ptt = PttState.IDLE))
    }

    // ------------------------------------------------------------------ PTT

    fun pttDown() {
        if (state.session != SessionState.LIVE) {
            update(state.copy(error = "会话未建立, 无法采集"))
            return
        }
        if (state.ptt == PttState.CAPTURING) return
        update(state.copy(ptt = PttState.CAPTURING, error = null))
        mic.start { block -> onMicBlock(block) }
    }

    fun pttUp() {
        if (state.ptt != PttState.CAPTURING) return
        mic.stop()
        coalescer.flushRemainder()
        update(state.copy(ptt = PttState.IDLE))
    }

    /** Mic blocks land here (production: MicSource callback; tests: direct). */
    fun onMicBlock(block: ByteArray) {
        coalescer.add(block)
    }

    // ---------------------------------------------------------------- heads

    fun loadHeads() {
        safeSend(HeadList(reqId = "hl-" + nextReqCounter()))
    }

    fun switchHead(name: String) {
        safeSend(HeadSwitch(reqId = "hs-" + nextReqCounter(), name = name))
        update(state.copy(switchNote = "切换中: $name …"))
    }

    // ------------------------------------------------------ GatewayListener

    override fun onState(cs: ConnectionState) {
        update(state.copy(connection = cs))
        if (cs == ConnectionState.CONNECTED && wantsSession) {
            // Reconnect path: the AND2-3 client already re-sent session.start
            // (resume) — reflect it and refresh the head row.
            update(state.copy(session = SessionState.STARTING, error = null))
            loadHeads()
        }
    }

    override fun onFrame(frame: WsFrame) {
        when (frame) {
            is SessionStarted -> update(
                state.copy(session = SessionState.LIVE, sessionId = frame.sessionId, error = null),
            )
            is SessionEnded -> update(state.copy(session = SessionState.ENDED))
            is HeadListResult -> update(state.copy(heads = parseHeads(frame.profiles), activeHead = frame.active))
            is HeadSwitchResult -> update(
                state.copy(
                    activeHead = if (frame.ok) frame.active else state.activeHead,
                    switchNote = frame.note ?: if (frame.ok) "已切换: ${frame.active}" else "切换失败",
                ),
            )
            is HeadTurn -> onHeadTurn(frame)
            is ErrorFrame -> update(state.copy(error = "${frame.code}: ${frame.message ?: frame.msg ?: ""}"))
            else -> Unit
        }
    }

    override fun onMedia(pcm: ByteArray) {
        // arrival accounting BEFORE any gating — the ②c fork: frames here but
        // silent ⇒ playback chain; frames absent ⇒ head/VAD upstream of us.
        val s0 = state
        val dropped = if (s0.muted) s0.downlinkMuteDropped + 1 else s0.downlinkMuteDropped
        update(
            s0.copy(
                downlinkFrames = s0.downlinkFrames + 1,
                downlinkBytes = s0.downlinkBytes + pcm.size,
                downlinkMuteDropped = dropped,
            ),
        )
        if (s0.muted) return // barge-in window: stale assistant audio is dropped
        player.enqueue(pcm)
    }

    override fun onError(t: Throwable) {
        update(state.copy(error = "传输错误: ${t.message ?: t.javaClass.simpleName}"))
    }

    // ------------------------------------------------------------- internals

    /**
     * Barge-in state machine (tk parity): user_start/interrupted → mute +
     * drop playback; assistant_start → unmute. user_text/assistant_* feed the
     * transcript. Returns true when the phase was recognised.
     */
    private fun onHeadTurn(frame: HeadTurn) {
        val phase = frame.phase ?: return
        when (phase) {
            "user_start", "interrupted" -> {
                player.dropAll()
                update(state.copy(phase = phase, muted = true, transcript = transcriptPlus(frame, phase)))
            }
            "assistant_start" -> {
                update(state.copy(phase = phase, muted = false, transcript = transcriptPlus(frame, phase)))
            }
            else -> update(state.copy(phase = phase, transcript = transcriptPlus(frame, phase)))
        }
    }

    private fun transcriptPlus(frame: HeadTurn, phase: String): List<TurnLine> {
        val line = TurnLine(phase, frame.detail, nowMs())
        return (state.transcript + line).takeLast(TRANSCRIPT_MAX)
    }

    private fun parseHeads(profiles: List<JsonElement>): List<HeadProfile> = profiles.mapNotNull { el ->
        val p = el as? JsonObject ?: return@mapNotNull null
        val name = (p["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val label = (p["label"] as? JsonPrimitive)?.contentOrNull ?: name
        HeadProfile(name, label)
    }

    private fun update(next: VoiceState) {
        state = next
        onChange?.invoke(next)
    }

    private fun safeSend(frame: ClientFrame) {
        try {
            send(frame)
        } catch (_: IllegalStateException) {
            // socket not open — reconnect cycle re-establishes; activation is
            // retried on CONNECTED via wantsSession
        }
    }

    /** True while the voice tab is the active page (set by the UI layer). */
    @Volatile
    var wantsSession: Boolean = false

    private val coalescer = ChunkCoalescer(sink = { out ->
        audio(out)
        state = state.copy(upstreamBlocks = state.upstreamBlocks + 1)
    })

    private var reqCounter = 0L
    private fun nextReqCounter(): Long = ++reqCounter

    companion object {
        const val TRANSCRIPT_MAX = 200
    }
}
