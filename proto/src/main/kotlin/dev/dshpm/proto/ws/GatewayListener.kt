package dev.dshpm.proto.ws

import dev.dshpm.proto.frame.ErrorFrame
import dev.dshpm.proto.frame.MalformedFrame
import dev.dshpm.proto.frame.UnknownFrame
import dev.dshpm.proto.frame.WsFrame

/** Callbacks from [VoiceGatewayClient]. All callbacks arrive on WS/scheduler threads. */
interface GatewayListener {

    /** State machine transitions (see [ConnectionState]). */
    fun onState(state: ConnectionState) {}

    /**
     * Every decoded inbound frame — typed acks/events, plus [UnknownFrame]
     * (unknown `t`, tolerated per spec §0) and [MalformedFrame] (bad JSON).
     * Deduplicated `pm.event` replays are NOT delivered here.
     */
    fun onFrame(frame: WsFrame) {}

    /** Downstream media frame — raw PCM bytes (spec §1: binary, no JSON envelope). */
    fun onMedia(pcm: ByteArray) {}

    /** Transport-level exceptions (never called for [ErrorFrame]s — those are frames). */
    fun onError(error: Throwable) {}

    companion object {
        val EMPTY: GatewayListener = object : GatewayListener {}
    }
}
