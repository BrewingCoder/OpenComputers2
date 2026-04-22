package com.brewingcoder.oc2.platform.script

import java.util.concurrent.ConcurrentHashMap

/**
 * Implicit per-peripheral lease. The first script to mutate a peripheral
 * implicitly leases it for the duration of that script's lifetime. A second
 * script trying to mutate the same peripheral gets [PeripheralLockException]
 * with the holder's PID + script name in the message.
 *
 * Read-only methods (`getSize`, `pollTouches`, `getEnergyStored`, etc.) skip
 * the lease check — many readers, one writer.
 *
 * Auto-released by [ScriptRunHandle] when the script ends (kill, crash, normal
 * exit). Scripts don't need to call any acquire/release explicitly — the lease
 * IS the script's lifetime.
 *
 * Identity-keyed: [acquire] uses `===` on the peripheral instance. Same Lua
 * handle from multiple `peripheral.find` calls maps to the same BE-side
 * Peripheral instance (the BE *is* the peripheral), so identity works.
 */
object PeripheralLease {

    data class Holder(val pid: Int, val chunkName: String, val onRelease: (() -> Unit)? = null)

    /** key = peripheral instance (identity); value = current holder. */
    private val leases: MutableMap<Any, Holder> = ConcurrentHashMap()

    /**
     * Throw [PeripheralLockException] if [peripheral] is held by a different
     * script. Otherwise (no lease, or held by us) take/keep the lease and return.
     *
     * [onRelease] is called once when the lease is dropped (script kill/crash/exit).
     * Only the first acquisition registers the callback — re-entrant calls are no-ops.
     *
     * No-op when no script context is active (tests, direct calls).
     */
    fun acquireOrThrow(peripheral: Any, onRelease: (() -> Unit)? = null) {
        val callerPid = ScriptCallerContext.pid() ?: return
        val callerName = ScriptCallerContext.chunkName() ?: "<unknown>"
        val existing = leases[peripheral]
        if (existing == null) {
            leases[peripheral] = Holder(callerPid, callerName, onRelease)
            return
        }
        if (existing.pid == callerPid) return  // re-entrant from same script — fine
        throw PeripheralLockException(
            "${peripheralLabel(peripheral)} is held by pid=${existing.pid} (${existing.chunkName}) -- kill it or wait"
        )
    }

    /** Drop every lease held by [pid], invoking each release callback. Called by [ScriptRunHandle] when a script ends. */
    fun releaseFor(pid: Int) {
        val released = mutableListOf<() -> Unit>()
        leases.entries.removeIf { entry ->
            (entry.value.pid == pid).also { matches ->
                if (matches) entry.value.onRelease?.let { released += it }
            }
        }
        released.forEach { runCatching { it() } }
    }

    /** Test/diagnostic — wipes all leases. */
    internal fun clearForTest() {
        leases.clear()
    }

    /** Diagnostic — current holder of [peripheral], or null if free. */
    fun holderOf(peripheral: Any): Holder? = leases[peripheral]

    private fun peripheralLabel(p: Any): String =
        (p as? com.brewingcoder.oc2.platform.peripheral.Peripheral)?.kind ?: p.javaClass.simpleName

    class PeripheralLockException(message: String) : RuntimeException(message)
}
