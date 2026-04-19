package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.WritableMount

/**
 * Abstract host for executing scripts inside an OC2 Computer. The shell's
 * `run <file>` command picks an implementation based on file extension; v0
 * has [CobaltLuaHost] (`.lua`) and [RhinoJSHost] (`.js`).
 *
 * Rule D: lives in `platform/`. The Cobalt impl depends on `org.squiddev.cobalt.*`,
 * Rhino impl on `org.mozilla.javascript.*` — both are pure JVM (no MC).
 */
interface ScriptHost {
    /**
     * Compile and run [source] against the supplied [env].
     *
     * Side channels exposed to the script:
     *   - `print(...)` — output captured into [ScriptEnv.out]
     *   - `fs.*` (see [ScriptFsOps]) — bound to [ScriptEnv.mount], cwd-resolved against [ScriptEnv.cwd]
     *   - `peripheral.find(kind)` — looks up a [Peripheral] via [ScriptEnv.findPeripheral]
     *
     * Returns synchronously; v0 has no cooperative yielding (intentional — see
     * `docs/12-followups.md`).
     */
    fun eval(source: String, chunkName: String, env: ScriptEnv): ScriptResult
}

/**
 * Per-execution environment passed to a [ScriptHost]. Bundles everything a
 * script can read or call into:
 *   - the per-computer filesystem
 *   - the cwd to resolve relative paths against
 *   - the print sink
 *   - a callback to look up peripherals on the host computer's wifi channel
 */
interface ScriptEnv {
    val mount: WritableMount
    val cwd: String
    val out: ShellOutput
    /** `kind`: e.g. `"monitor"`. Returns null if no peripheral of that kind is on the host's channel. */
    fun findPeripheral(kind: String): Peripheral?
    /** All peripherals on the host's channel matching [kind], or all peripherals if [kind] is null. */
    fun listPeripherals(kind: String? = null): List<Peripheral> = emptyList()
    /** Inbox + broadcast for `network.send/recv/peek/size/id`. Default is inert (tests). */
    val network: NetworkAccess get() = NetworkAccess.NOOP

    /**
     * Event queue this script reads via `os.pullEvent`. Default is a fresh
     * always-empty queue (tests that don't need events). Production passes the
     * per-script handle's queue.
     */
    val events: ScriptEventQueue get() = ScriptEventQueue()
}

data class ScriptResult(
    val ok: Boolean,
    val errorMessage: String?,
)
