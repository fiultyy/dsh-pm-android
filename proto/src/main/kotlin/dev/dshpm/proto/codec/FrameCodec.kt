package dev.dshpm.proto.codec

import dev.dshpm.proto.frame.WsFrame

/**
 * Public codec facade over the v1 frozen frame set (spec-ws-protocol-v1.md).
 *
 * - [encode] turns any typed frame into wire JSON text.
 * - [decode] turns wire text into a typed frame; unknown `t` yields
 *   [dev.dshpm.proto.frame.UnknownFrame] and malformed text yields
 *   [dev.dshpm.proto.frame.MalformedFrame] — decode never throws.
 *
 * Media frames (raw PCM up/down) are binary WebSocket frames with no JSON
 * envelope; they never pass through this codec.
 */
object FrameCodec {
    fun encode(frame: WsFrame): String = FrameEncoder.encode(frame)
    fun decode(text: String): WsFrame = FrameDecoder.decode(text)
}
