package com.brewingcoder.oc2.platform.script

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * One event delivered to a running script via `os.pullEvent`. Names mirror
 * CC:Tweaked conventions for player-familiarity: `"monitor_touch"`,
 * `"network_message"`, `"timer"`, etc.
 *
 * [args] holds the per-event payload positionally — Lua binding splats them
 * back into multiple return values from `os.pullEvent`. Types are restricted
 * to what both Lua and JS coerce cleanly: String, Int, Double, Boolean.
 */
data class ScriptEvent(
    val name: String,
    val args: List<Any?> = emptyList(),
)

/**
 * Per-script blocking event queue. The script worker thread blocks on [poll];
 * event sources (BE tick, peripheral handlers) push via [offer]. Bounded so a
 * busy event source can't OOM the JVM.
 *
 * Filter semantics (Phase 1, intentionally simple):
 *   - poll(filter=null) returns the next event of any name
 *   - poll(filter="x") drops non-matching events while waiting; returns the
 *     first matching one within the timeout
 *
 * CC:T's "queue non-matching events for next pullEvent" semantics are nicer
 * but require a re-queue dance that's easy to get wrong; revisit when scripts
 * actually need it.
 */
class ScriptEventQueue {

    private val queue: LinkedBlockingDeque<ScriptEvent> = LinkedBlockingDeque(QUEUE_CAP)

    /**
     * Push [event]. Drops oldest event if full — matches the
     * NetworkInboxes design (bounded, drop-oldest, predictable memory).
     */
    fun offer(event: ScriptEvent) {
        while (!queue.offer(event)) {
            queue.pollFirst() ?: break  // someone else drained — try once more
        }
    }

    /**
     * Block up to [timeoutMs] waiting for an event matching [filter] (or any
     * event when filter is null). Returns null on timeout. Non-matching
     * events are dropped (Phase 1 — see class doc).
     *
     * Caller must respect interruption: a kill flag will Thread.interrupt
     * the worker, which makes [poll] throw [InterruptedException] — propagate
     * it so the script tears down cleanly.
     */
    fun poll(filter: String?, timeoutMs: Long): ScriptEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            val e = queue.pollFirst(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (filter == null || e.name == filter) return e
            // Phase 1: drop. Phase 2 may keep them for a future unfiltered poll.
        }
    }

    fun clear() = queue.clear()
    fun size(): Int = queue.size

    companion object {
        const val QUEUE_CAP: Int = 256
    }
}
