package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.platform.os.ScriptRunner
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.ScriptHost
import com.brewingcoder.oc2.platform.script.ScriptRunHandle
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-BE [ScriptRunner] impl. Owns at most one in-flight [ScriptRunHandle].
 * The owning [ComputerBlockEntity] polls [current] from its tick to drain
 * output and detect completion.
 *
 * One-script-at-a-time policy: starting while another is running returns
 * [ScriptRunner.StartResult.AlreadyRunning]. Use `kill` to make room.
 */
class BeScriptRunner : ScriptRunner {

    private var handle: ScriptRunHandle? = null

    @Synchronized
    override fun start(
        host: ScriptHost,
        source: String,
        chunkName: String,
        mount: WritableMount,
        cwd: String,
        peripheralFinder: (String) -> Peripheral?,
    ): ScriptRunner.StartResult {
        val existing = handle
        if (existing != null && !existing.isDone()) {
            return ScriptRunner.StartResult.AlreadyRunning(existing)
        }
        val pid = NEXT_PID.getAndIncrement()
        val h = ScriptRunHandle(pid, chunkName, host, source, mount, cwd, peripheralFinder)
        h.start()
        handle = h
        return ScriptRunner.StartResult.Started(h)
    }

    @Synchronized
    override fun current(): ScriptRunHandle? = handle

    @Synchronized
    override fun kill(): Boolean {
        val h = handle ?: return false
        if (h.isDone()) return false
        h.kill()
        return true
    }

    /** Called by the BE on completion to release the slot for the next run. */
    @Synchronized
    fun clearIfDone(): ScriptRunHandle? {
        val h = handle ?: return null
        if (!h.isDone()) return null
        handle = null
        return h
    }

    companion object {
        /** Process IDs for `ps`. Monotonic across all BEs in the JVM — fine for v0. */
        private val NEXT_PID = AtomicInteger(1)
    }
}
