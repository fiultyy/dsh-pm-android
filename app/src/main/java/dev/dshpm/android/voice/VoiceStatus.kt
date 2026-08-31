package dev.dshpm.android.voice

/**
 * Voice-page status derivation (AND4-3 ①): the MAIN status is PTT-first —
 * while the button is held the board must read 收音中 no matter what turn
 * events arrive mid-hold (a barge-in `interrupted` promoted to the main
 * slot reads as "recording failed" to users). Turn events stay in the
 * transcript (⚡interrupted …) instead of hijacking the status.
 */
object VoiceStatus {

    /** Transcript/turn icon for a phase (interrupted reads as a turn event, not a failure). */
    fun turnIcon(phase: String?): String = when (phase) {
        "user_start" -> "🎤"
        "user_end" -> "…"
        "user_text" -> "📝"
        "assistant_start" -> "🔊"
        "assistant_end" -> "✔"
        "interrupted" -> "⚡"
        "yield" -> "🤝" // AND4-4 server-driven 让位 note
        else -> "·"
    }

    /**
     * Main status (icon, label). Capturing wins over everything; otherwise
     * the turn phase drives it, with interrupted shown only outside a hold.
     */
    fun mainVisual(capturing: Boolean, phase: String?): Pair<String, String> =
        if (capturing) "🎤" to "收音中"
        else when (phase) {
            "user_start" -> "🎤" to "聆听中"
            "user_end" -> "…" to "理解中"
            "user_text" -> "📝" to "已识别"
            "assistant_start" -> "🔊" to "播报中"
            "assistant_end" -> "✔" to "回合结束"
            "interrupted" -> "✂" to "已打断"
            else -> "💤" to "空闲"
        }
}
