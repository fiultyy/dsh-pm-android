package dev.dshpm.proto.ws

/** Lifecycle of [VoiceGatewayClient]. */
enum class ConnectionState {
    /** Fresh, never connected. */
    IDLE,

    /** TCP/WS handshake in flight. */
    CONNECTING,

    /** Socket open, `auth` sent, waiting for `auth.ok`. */
    AUTHENTICATING,

    /** Authenticated; app-level ping heartbeat running. */
    CONNECTED,

    /** Dropped, waiting out the backoff before reconnect. */
    RECONNECT_WAIT,

    /** Terminal: user close or fatal auth rejection (`auth`/`concurrent_limit`). */
    CLOSED,
}
