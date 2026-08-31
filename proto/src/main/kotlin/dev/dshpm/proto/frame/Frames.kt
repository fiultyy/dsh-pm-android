package dev.dshpm.proto.frame

import kotlinx.serialization.json.JsonObject

/**
 * Root of the v1 frame hierarchy (spec-ws-protocol-v1.md).
 * Text frames are single JSON objects routed by key `t`.
 */
sealed interface WsFrame {
    /** Frozen wire literal of this frame type. */
    val t: String
}

/**
 * Unknown-but-tolerated frame. Spec §0 consumer duty: ignore unknown frame
 * types (and unknown fields) — never crash, never disconnect.
 */
data class UnknownFrame(
    override val t: String,
    val raw: JsonObject?,
) : WsFrame

/** Unparseable text frame — surfaced, never crashes the connection. */
data class MalformedFrame(
    val text: String,
    val error: String,
) : WsFrame {
    override val t: String get() = "<malformed>"
}
