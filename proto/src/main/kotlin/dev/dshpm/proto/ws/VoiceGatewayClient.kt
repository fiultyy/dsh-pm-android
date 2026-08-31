package dev.dshpm.proto.ws

import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.ClientFrame
import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.Ping
import dev.dshpm.proto.frame.PmEvent
import dev.dshpm.proto.frame.Pong
import dev.dshpm.proto.frame.SessionStart
import dev.dshpm.proto.frame.SessionStarted
import dev.dshpm.proto.codec.FrameCodec
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.Closeable
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Voice-gateway WS client over the v1 frozen frame set.
 *
 * Responsibilities (AND2-3):
 * - `auth{token}` on every (re)connect; terminal on `error{code:auth|concurrent_limit}`.
 * - App-level `ping` heartbeat (answered by `pong{ts}`); presumed-dead after
 *   [Config.missedPongReconnectLimit] silent intervals → forced reconnect.
 * - Reconnect with exponential backoff; after re-auth, a previously started
 *   session is resumed via `session.start{session_id}` (TK-001: resume/reseed).
 * - Snapshot replay reconciliation: `pm.event` frames pass through a
 *   `(source,msgid)` dedup window (PM-007 semantics) so replays are suppressed.
 * - Tolerance: unknown frames / bad JSON are surfaced, never fatal.
 *
 * Media: [sendAudio] pushes raw PCM up (binary frame); downstream PCM lands
 * in [GatewayListener.onMedia]. Sending audio before `session.start` triggers
 * `error{code:no_session}` server-side (spec §1) — the client does not gate it.
 *
 * Threading: callbacks fire on Java-WebSocket reader threads or the internal
 * scheduler. [close] is idempotent and terminal (no reconnect afterwards).
 */
class VoiceGatewayClient(
    val uri: URI,
    private val token: String,
    private val listener: GatewayListener = GatewayListener.EMPTY,
    val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

    data class Config(
        val pingIntervalMillis: Long = 20_000,
        val missedPongReconnectLimit: Int = 2,
        val initialReconnectDelayMillis: Long = 500,
        val maxReconnectDelayMillis: Long = 30_000,
        val reconnectDelayFactor: Double = 2.0,
        val resumeSessionOnReconnect: Boolean = true,
        val dedup: MsgIdDedup = MsgIdDedup(),
    )

    private val lock = Any()

    @Volatile
    var state: ConnectionState = ConnectionState.IDLE
        private set

    /** session_id of the last successful auth.ok (retained across reconnects). */
    @Volatile
    var sessionId: String? = null
        private set

    /** Timestamp of the last app-level pong (clock units). */
    @Volatile
    var lastPongAt: Long = 0L
        private set

    /** pm.event frames suppressed by the dedup window (observability). */
    val suppressedReplays: Long get() = config.dedup.duplicateCount

    private var scheduler: ScheduledExecutorService? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var reconnectTask: ScheduledFuture<*>? = null

    @Volatile
    private var ws: InnerWs? = null

    @Volatile
    private var userClosed = false

    @Volatile
    private var authRejected = false

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var sessionStarted = false

    @Volatile
    private var lastStartedSessionId: String? = null

    // ------------------------------------------------------------------ API

    /** Starts the first connection attempt (async — watch [GatewayListener.onState]). */
    fun connect() {
        synchronized(lock) {
            check(state == ConnectionState.IDLE || state == ConnectionState.CLOSED) {
                "connect() allowed only from IDLE/CLOSED, was $state"
            }
            userClosed = false
            authRejected = false
            if (scheduler == null) scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "dshpm-gw-client").apply { isDaemon = true }
            }
        }
        openSocket()
    }

    /** Sends a control frame. Throws [IllegalStateException] when the socket is not open. */
    fun send(frame: ClientFrame) {
        val socket = ws
        check(socket != null && socket.isOpen) { "connection not open (state=$state)" }
        if (frame is SessionStart) sessionStarted = true
        socket.send(FrameCodec.encode(frame))
    }

    /** Sends an upstream media frame — raw PCM, binary WebSocket frame (spec §1). */
    fun sendAudio(pcm: ByteArray) {
        val socket = ws
        check(socket != null && socket.isOpen) { "connection not open (state=$state)" }
        socket.send(ByteBuffer.wrap(pcm))
    }

    /** User-initiated close: terminal, cancels reconnect + heartbeat. Idempotent. */
    override fun close() {
        synchronized(lock) {
            userClosed = true
            pingTask?.cancel(false)
            reconnectTask?.cancel(false)
            pingTask = null
            reconnectTask = null
        }
        ws?.close()
        setState(ConnectionState.CLOSED)
    }

    // ------------------------------------------------------------- internals

    private fun openSocket() {
        if (userClosed) return
        setState(ConnectionState.CONNECTING)
        val socket = InnerWs(uri)
        synchronized(lock) { ws = socket }
        socket.connect() // async; Java-WebSocket spawns its own thread
    }

    private fun scheduleReconnect() {
        if (userClosed || authRejected) {
            setState(ConnectionState.CLOSED)
            return
        }
        setState(ConnectionState.RECONNECT_WAIT)
        val factor = config.reconnectDelayFactor.pow(reconnectAttempt.toDouble())
        val delay = min(
            (config.initialReconnectDelayMillis * factor).toLong(),
            config.maxReconnectDelayMillis,
        )
        reconnectAttempt++
        synchronized(lock) {
            reconnectTask = scheduler?.schedule(
                { openSocket() },
                delay,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun startPing() {
        stopPing()
        lastPongAt = clock()
        pingTask = scheduler?.scheduleAtFixedRate(
            {
                try {
                    val silence = clock() - lastPongAt
                    if (silence > config.pingIntervalMillis * (config.missedPongReconnectLimit + 1)) {
                        // Presumed dead — force the reconnect path via close.
                        ws?.close()
                        return@scheduleAtFixedRate
                    }
                    if (state == ConnectionState.CONNECTED) send(Ping)
                } catch (t: Throwable) {
                    listener.onError(t)
                }
            },
            config.pingIntervalMillis,
            config.pingIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopPing() {
        synchronized(lock) {
            pingTask?.cancel(false)
            pingTask = null
        }
    }

    private fun setState(next: ConnectionState) {
        state = next
        listener.onState(next)
    }

    /** Fatal auth rejections end the client (a bad token would loop forever). */
    private fun isFatalAuthError(frame: ErrorFrame): Boolean =
        frame.code == "auth" || frame.code == "concurrent_limit"

    private inner class InnerWs(endpoint: URI) : WebSocketClient(endpoint) {

        init {
            // Disable lib-level protocol pings; the v1 protocol defines its own
            // JSON ping/pong heartbeat.
            setConnectionLostTimeout(0)
        }

        override fun onOpen(handshakedata: ServerHandshake?) {
            setState(ConnectionState.AUTHENTICATING)
            send(FrameCodec.encode(dev.dshpm.proto.frame.Auth(token)))
        }

        override fun onMessage(message: String?) {
            val text = message ?: return
            val frame = FrameCodec.decode(text)
            when (frame) {
                is AuthOk -> {
                    sessionId = frame.sessionId
                    reconnectAttempt = 0
                    lastPongAt = clock()
                    startPing()
                    // Resume BEFORE notifying CONNECTED so observers never race the resume send.
                    maybeResumeSession()
                    setState(ConnectionState.CONNECTED)
                }
                is Pong -> lastPongAt = clock()
                is SessionStarted -> lastStartedSessionId = frame.sessionId
                is ErrorFrame -> if (isFatalAuthError(frame)) authRejected = true
                else -> Unit
            }
            if (frame is PmEvent && config.dedup.seen(frame.source, frame.msgid)) {
                return // replay duplicate — suppressed, spec PM-007 reconciliation
            }
            listener.onFrame(frame)
        }

        override fun onMessage(bytes: ByteBuffer?) {
            bytes ?: return
            val pcm = ByteArray(bytes.remaining())
            bytes.get(pcm)
            listener.onMedia(pcm)
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            stopPing()
            if (userClosed || authRejected) {
                setState(ConnectionState.CLOSED)
            } else {
                scheduleReconnect()
            }
        }

        override fun onError(ex: Exception?) {
            listener.onError(ex ?: RuntimeException("unknown ws error"))
        }

        private fun maybeResumeSession() {
            if (!config.resumeSessionOnReconnect) return
            if (!sessionStarted) return
            val resumeId = lastStartedSessionId ?: sessionId ?: return
            send(FrameCodec.encode(SessionStart(sessionId = resumeId)))
        }
    }
}
