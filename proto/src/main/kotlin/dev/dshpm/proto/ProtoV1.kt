package dev.dshpm.proto

/**
 * v1 frozen constants — spec-ws-protocol-v1.md (§4/§5/§7).
 * Frame field names and semantics are frozen ("only-add", never change).
 */
object ProtoV1 {

    /** WSP-001: auth.ok optional self-declaring protocol version. */
    const val PROTO: String = "v1"

    /** Total frozen frame set: 18 inbound + 16 ack + 14 event + 2 media. */
    const val FRAME_SET_SIZE: Int = 50

    const val GATEWAY_PATH: String = "/ws"
    const val DEFAULT_HOST: String = "127.0.0.1"
    const val DEFAULT_PORT: Int = 8765

    /** ORCH_KINDS (7) — orchestration plane event kinds. */
    val ORCH_KINDS: List<String> = listOf(
        "orch.dispatch", "orch.ack", "orch.progress", "orch.gate",
        "orch.done", "orch.failed", "orch.metrics",
    )

    /** TOPIC_KINDS (6) — observation plane event kinds. */
    val TOPIC_KINDS: List<String> = listOf(
        "head.turn", "head.compact", "fleet.snapshot", "bridge.msg",
        "tickets.snapshot", "body.push",
    )

    /** SUBSCRIBABLE_KINDS (13) = ORCH + TOPIC. */
    val SUBSCRIBABLE_KINDS: List<String> = ORCH_KINDS + TOPIC_KINDS

    /** Default subscription for a voice session (§4). */
    val DEFAULT_VOICE_KINDS: List<String> = ORCH_KINDS + listOf("head.turn", "body.push")

    /** Error codes observed on the `error` frame (§3, additive-only). */
    val ERROR_CODES: Set<String> = setOf(
        "bad_json", "unauthorized", "bad_type", "bad_state", "auth", "concurrent_limit",
        "internal", "bad_request", "body_miss", "lane", "pm_sub_failed", "pm_sub_ended",
        "no_session", "observe_media", "rate",
    )
}
