package com.brewingcoder.oc2.platform.script

/**
 * Thread-local "which script is currently executing" so peripheral implementations
 * can identify the caller for per-peripheral leasing — without threading a PID
 * argument through every Peripheral interface method.
 *
 * Set by [ScriptRunHandle] when the worker thread starts; cleared on exit.
 *
 * Returns null when no script context is active — peripheral implementations
 * should treat this as "not script-driven" (e.g. unit tests, server-side direct
 * calls) and skip the lease check.
 */
object ScriptCallerContext {
    private val tlPid: ThreadLocal<Int?> = ThreadLocal()
    private val tlName: ThreadLocal<String?> = ThreadLocal()

    fun set(pid: Int, chunkName: String) {
        tlPid.set(pid)
        tlName.set(chunkName)
    }

    fun clear() {
        tlPid.remove()
        tlName.remove()
    }

    /** PID of the script currently calling on this thread, or null if none. */
    fun pid(): Int? = tlPid.get()

    /** Display name of the script currently calling on this thread, or null. */
    fun chunkName(): String? = tlName.get()
}
