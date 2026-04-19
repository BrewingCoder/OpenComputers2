package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ScriptRunner
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.ScriptHost
import com.brewingcoder.oc2.platform.script.ScriptRunHandle
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-BE [ScriptRunner] impl. Holds at most ONE foreground script (output
 * routed to the open terminal) plus any number of background scripts (output
 * drained but not displayed; managed via `bg`/`jobs`/`fg`/`kill` shell cmds).
 *
 * Foreground rule: only one at a time. Starting a foreground script while one
 * is running returns [ScriptRunner.StartResult.AlreadyRunning] — use `kill` or
 * `bg` to make room.
 *
 * Background rule: unbounded count. Each gets its own worker thread + event
 * queue. `fg <pid>` promotes a background to foreground (only allowed when no
 * foreground is currently running).
 */
class BeScriptRunner : ScriptRunner {

    private var foreground: ScriptRunHandle? = null
    private val background: MutableList<ScriptRunHandle> = mutableListOf()

    @Synchronized
    override fun start(
        host: ScriptHost,
        source: String,
        chunkName: String,
        mount: WritableMount,
        cwd: String,
        peripheralFinder: (String) -> Peripheral?,
        peripheralLister: (String?) -> List<Peripheral>,
        networkAccess: NetworkAccess,
    ): ScriptRunner.StartResult {
        val existing = foreground
        if (existing != null && !existing.isDone()) {
            return ScriptRunner.StartResult.AlreadyRunning(existing)
        }
        val h = spawn(host, source, chunkName, mount, cwd, peripheralFinder, peripheralLister, networkAccess)
        foreground = h
        return ScriptRunner.StartResult.Started(h)
    }

    @Synchronized
    override fun startBackground(
        host: ScriptHost,
        source: String,
        chunkName: String,
        mount: WritableMount,
        cwd: String,
        peripheralFinder: (String) -> Peripheral?,
        peripheralLister: (String?) -> List<Peripheral>,
        networkAccess: NetworkAccess,
    ): ScriptRunner.StartResult.Started {
        val h = spawn(host, source, chunkName, mount, cwd, peripheralFinder, peripheralLister, networkAccess)
        background.add(h)
        return ScriptRunner.StartResult.Started(h)
    }

    @Synchronized
    override fun current(): ScriptRunHandle? = foreground

    @Synchronized
    override fun all(): List<ScriptRunHandle> {
        val list = ArrayList<ScriptRunHandle>(background.size + 1)
        foreground?.let { list.add(it) }
        list.addAll(background)
        return list
    }

    @Synchronized
    override fun kill(): Boolean {
        val h = foreground ?: return false
        if (h.isDone()) return false
        h.kill()
        return true
    }

    @Synchronized
    override fun killByPid(pid: Int): Boolean {
        val h = (foreground.takeIf { it?.pid == pid }
            ?: background.firstOrNull { it.pid == pid })
            ?: return false
        if (h.isDone()) return false
        h.kill()
        return true
    }

    @Synchronized
    override fun moveToForeground(pid: Int): Boolean {
        val target = background.firstOrNull { it.pid == pid && !it.isDone() } ?: return false
        // Only allowed when foreground is empty / done — fg has no "swap" semantics.
        if (foreground?.let { !it.isDone() } == true) return false
        background.remove(target)
        foreground = target
        return true
    }

    /** Sweep finished handles. Foreground BE calls this during tick after draining output. */
    @Synchronized
    fun clearIfDone(): ScriptRunHandle? {
        val finishedFg = foreground?.takeIf { it.isDone() }
        if (finishedFg != null) foreground = null
        background.removeAll { it.isDone() }
        return finishedFg
    }

    private fun spawn(
        host: ScriptHost, source: String, chunkName: String,
        mount: WritableMount, cwd: String,
        peripheralFinder: (String) -> Peripheral?,
        peripheralLister: (String?) -> List<Peripheral>,
        networkAccess: NetworkAccess,
    ): ScriptRunHandle {
        val pid = NEXT_PID.getAndIncrement()
        val h = ScriptRunHandle(pid, chunkName, host, source, mount, cwd, peripheralFinder, peripheralLister, networkAccess)
        h.start()
        return h
    }

    companion object {
        /** Process IDs for `ps`/`jobs`. Monotonic across all BEs in the JVM. */
        private val NEXT_PID = AtomicInteger(1)
    }
}
