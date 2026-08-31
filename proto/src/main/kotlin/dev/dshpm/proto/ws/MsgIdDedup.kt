package dev.dshpm.proto.ws

/**
 * Replay-reconciliation dedup window — PM-007 semantics, mirrored client-side.
 *
 * - Key: `(source, msgid)` — the same msgid from two different sources is NOT
 *   a duplicate (spec-pm-host-service.md PM-007: "事件 `(source,msgid)` 去重").
 * - Window: 60s TTL.
 * - GC: when the window exceeds 1000 entries it is truncated to the newest half.
 *
 * Purpose: after reconnect the gateway replays the snapshot before increments;
 * events already delivered pre-drop arrive again and must be suppressed so the
 * consumer sees each `(source,msgid)` exactly once ("断线重连快照+增量无缝").
 */
class MsgIdDedup(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val gcThreshold: Int = DEFAULT_GC_THRESHOLD,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(clock: () -> Long) : this(DEFAULT_TTL_MILLIS, DEFAULT_GC_THRESHOLD, clock)

    private data class Key(val source: String?, val msgid: String?)

    private val window = LinkedHashMap<Key, Long>()

    /** Number of duplicates suppressed so far (observability). */
    @Volatile
    var duplicateCount: Long = 0
        private set

    /**
     * Records `(source, msgid)`.
     * @return true when this is a **duplicate** (already inside the live window) —
     *         the caller should suppress delivery.
     *         Frames without a msgid always return false (nothing to dedup on).
     */
    @Synchronized
    fun seen(source: String?, msgid: String?): Boolean {
        if (msgid == null) return false
        val key = Key(source, msgid)
        val now = clock()

        // Fast-path TTL eviction (insertion order tracks time order for
        // monotonic clocks; a full sweep also happens inside gc()).
        val it = window.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value >= ttlMillis) it.remove() else break
        }

        if (window.containsKey(key)) {
            duplicateCount++
            return true
        }
        window[key] = now
        if (window.size > gcThreshold) gc(now)
        return false
    }

    /** Current live window size (observability / tests). */
    @Synchronized
    fun size(): Int = window.size

    @Synchronized
    fun reset() {
        window.clear()
        duplicateCount = 0
    }

    private fun gc(now: Long) {
        window.entries.removeAll { now - it.value >= ttlMillis }
        if (window.size > gcThreshold) {
            val keepKeys = window.entries
                .sortedByDescending { it.value }
                .take(window.size / 2)
                .map { it.key }
                .toHashSet()
            window.keys.retainAll(keepKeys)
        }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 60_000
        const val DEFAULT_GC_THRESHOLD: Int = 1000
    }
}
