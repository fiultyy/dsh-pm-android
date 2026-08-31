package dev.dshpm.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** AND4-3 ①: PTT-held wins the main status; barge-in stays a transcript event. */
class VoiceStatusTest {

    @Test
    fun capturingOverridesInterruptedPhase() {
        // the reported defect: mid-hold barge-in promoted "已打断" over "收音中"
        val (icon, label) = VoiceStatus.mainVisual(capturing = true, phase = "interrupted")
        assertEquals("🎤", icon)
        assertEquals("收音中", label)
    }

    @Test
    fun capturingOverridesEveryPhase() {
        for (phase in listOf("user_start", "user_end", "user_text", "assistant_start", "assistant_end", null)) {
            assertEquals("收音中", VoiceStatus.mainVisual(capturing = true, phase = phase).second)
        }
    }

    @Test
    fun outsideHoldInterruptedStillShowsAsTurnState() {
        assertEquals("已打断", VoiceStatus.mainVisual(capturing = false, phase = "interrupted").second)
        assertEquals("空闲", VoiceStatus.mainVisual(capturing = false, phase = null).second)
    }

    @Test
    fun interruptedReadsAsTurnEventIconInTranscript() {
        assertEquals("⚡", VoiceStatus.turnIcon("interrupted"))
        assertEquals("🎤", VoiceStatus.turnIcon("user_start"))
    }
}
