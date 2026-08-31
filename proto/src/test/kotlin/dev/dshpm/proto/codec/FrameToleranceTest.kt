package dev.dshpm.proto.codec

import dev.dshpm.proto.frame.AuthOk
import dev.dshpm.proto.frame.MalformedFrame
import dev.dshpm.proto.frame.UnknownFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forward-compatibility tolerance (spec §0 "只增不改"):
 * unknown `t` → UnknownFrame; unknown fields on known frames ignored;
 * malformed text → MalformedFrame. Decode never throws, never kills the link.
 */
class FrameToleranceTest {

    @Test fun unknownFrameTypeYieldsUnknownFrame() {
        val f = FrameCodec.decode("""{"t":"future.frame","x":1,"nested":{"a":[1,2]}}""")
        assertTrue(f is UnknownFrame)
        f as UnknownFrame
        assertEquals("future.frame", f.t)
        assertEquals(3, f.raw?.size) // t + x + nested preserved verbatim
    }

    @Test fun unknownOptionalFieldOnKnownFrameIsIgnored() {
        // v1.x adds an optional field — old client must still decode the frame.
        val f = FrameCodec.decode("""{"t":"auth.ok","session_id":"s-1","proto":"v1","future_field":{"deep":[1]}}""")
        assertEquals(AuthOk(sessionId = "s-1", proto = "v1"), f)
    }

    @Test fun malformedJsonYieldsMalformedFrame() {
        val f = FrameCodec.decode("""{"t":"auth","token": "unterminated""")
        assertTrue(f is MalformedFrame)
    }

    @Test fun jsonArrayIsMalformed() {
        assertTrue(FrameCodec.decode("[1,2,3]") is MalformedFrame)
    }

    @Test fun jsonScalarIsMalformed() {
        assertTrue(FrameCodec.decode("\"just a string\"") is MalformedFrame)
    }

    @Test fun missingRoutingKeyIsMalformed() {
        assertTrue(FrameCodec.decode("""{"token":"x"}""") is MalformedFrame)
    }

    @Test fun nonStringRoutingKeyIsMalformed() {
        assertTrue(FrameCodec.decode("""{"t":42}""") is MalformedFrame)
    }

    @Test fun emptyStringIsMalformed() {
        assertTrue(FrameCodec.decode("") is MalformedFrame)
    }

    @Test fun malformedFrameKeepsOriginalTextForDiagnostics() {
        val f = FrameCodec.decode("{oops") as MalformedFrame
        assertEquals("{oops", f.text)
        assertTrue(f.error.isNotBlank())
    }

    @Test fun unknownFrameRoundtripsVerbatim() {
        val wire = """{"t":"v2.thing","p":1}"""
        val f = FrameCodec.decode(wire) as UnknownFrame
        assertEquals(wire, FrameCodec.encode(f))
    }
}
