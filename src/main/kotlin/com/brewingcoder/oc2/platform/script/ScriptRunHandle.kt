package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handle to a script running on its own worker thread. Exposes:
 *   - the in-flight output buffer (server tick drains + flushes to client)
 *   - completion status (server tick polls this; on done, sends final flush)
 *   - kill switch (interrupts the worker thread)
 *
 * Lifecycle:
 *   1. Caller constructs with a [ScriptHost] + source + an [env factory] that
 *      creates the per-execution [ScriptEnv] with a thread-safe output sink
 *   2. [start] spawns the worker thread and returns immediately
 *   3. [drainOutput] / [isDone] / [result] polled by the BE tick
 *   4. [kill] aborts (Thread.interrupt; relies on script reaching `sleep` or
 *      a peripheral marshal to actually exit)
 *
 * Thread-safety: output buffer is concurrent; status flags are volatile.
 *
 * Naming: "PID" = the integer assigned by [com.brewingcoder.oc2.block.ComputerBlockEntity]
 * for the `ps` shell command's display. Not a real OS PID.
 */
class ScriptRunHandle(
    val pid: Int,
    val chunkName: String,
    private val host: ScriptHost,
    private val source: String,
    private val mount: WritableMount,
    private val cwd: String,
    private val peripheralFinder: (String) -> Peripheral?,
    private val peripheralLister: (String?) -> List<Peripheral> = { emptyList() },
    private val networkAccess: NetworkAccess = NetworkAccess.NOOP,
) {

    private val outputQueue: ConcurrentLinkedQueue<OutputItem> = ConcurrentLinkedQueue()
    private val killFlag: AtomicBoolean = AtomicBoolean(false)

    /**
     * Per-script bounded ring of recent output lines. Captured at offer-time so
     * background scripts (whose `outputQueue` is drained-and-dropped) still have
     * a viewable history via `tail <pid>` shell command and the oc2-debug
     * `read_script_log` tool. Bounded to [TAIL_BUFFER_LINES] entries.
     */
    private val tailBuffer: ConcurrentLinkedDeque<String> = ConcurrentLinkedDeque()

    /** Push a line into the bounded tail buffer, evicting the oldest if over cap. */
    private fun appendTail(line: String) {
        tailBuffer.addLast(line)
        while (tailBuffer.size > TAIL_BUFFER_LINES) tailBuffer.pollFirst()
    }

    /** Per-script event queue exposed to the env's `os.pullEvent` binding. */
    val eventQueue: ScriptEventQueue = ScriptEventQueue()

    @Volatile private var thread: Thread? = null
    @Volatile private var done: Boolean = false
    @Volatile private var resultRef: ScriptResult? = null

    /** Unioned output stream: text lines AND clear-screen markers. */
    sealed interface OutputItem {
        data class Line(val text: String) : OutputItem
        data object Clear : OutputItem
    }

    companion object {
        /** Sentinel error message for kills — the BE checks this to print the right banner. */
        const val KILLED: String = "killed"
        /** Per-script tail-buffer cap. Same order of magnitude as the per-BE foreground buffer. */
        const val TAIL_BUFFER_LINES: Int = 200
    }

    /** Snapshot of the recent output, oldest → newest. Up to [TAIL_BUFFER_LINES] entries. */
    fun tail(): List<String> = tailBuffer.toList()

    fun isDone(): Boolean = done
    fun result(): ScriptResult? = resultRef
    fun isKilled(): Boolean = killFlag.get()

    fun start() {
        check(thread == null) { "already started" }
        val t = Thread({
            ScriptCallerContext.set(pid, chunkName)
            try {
                val r = host.eval(source, chunkName, makeEnv())
                // If the kill flag was raised, the eval likely terminated via an
                // InterruptedException wrapped in a LuaError / RhinoError. Override
                // the noisy "lua error: ... InterruptedException" with a clean signal.
                resultRef = if (killFlag.get()) ScriptResult(ok = false, errorMessage = KILLED) else r
            } catch (ie: InterruptedException) {
                resultRef = ScriptResult(ok = false, errorMessage = KILLED)
            } catch (t: Throwable) {
                resultRef = ScriptResult(ok = false, errorMessage = "worker crashed: ${t::class.simpleName}: ${t.message}")
            } finally {
                done = true
                // Release any peripheral leases this script held — next script
                // touching those peripherals takes them over cleanly.
                PeripheralLease.releaseFor(pid)
                ScriptCallerContext.clear()
                // Stamp the final result into the tail buffer so `tail <pid>` shows
                // why a script ended even after its outputQueue is drained.
                resultRef?.errorMessage?.let { appendTail("[end] $it") }
            }
        }, "OC2 script worker pid=$pid").apply { isDaemon = true }
        thread = t
        t.start()
    }

    /** Drain the accumulated output items. Safe to call from any thread. */
    fun drainOutput(): List<OutputItem> {
        val drained = mutableListOf<OutputItem>()
        while (true) {
            val item = outputQueue.poll() ?: break
            drained.add(item)
        }
        return drained
    }

    fun kill() {
        killFlag.set(true)
        thread?.interrupt()
    }

    private fun makeEnv(): ScriptEnv = object : ScriptEnv {
        override val mount: WritableMount = this@ScriptRunHandle.mount
        override val cwd: String = this@ScriptRunHandle.cwd
        override val out: ShellOutput = object : ShellOutput {
            override fun println(line: String) {
                if (killFlag.get()) throw InterruptedException("killed")
                outputQueue.offer(OutputItem.Line(line))
                appendTail(line)
            }
            override fun clear() {
                if (killFlag.get()) throw InterruptedException("killed")
                outputQueue.offer(OutputItem.Clear)
                appendTail("--- clear ---")
            }
        }
        override fun findPeripheral(kind: String): Peripheral? = peripheralFinder(kind)
        override fun listPeripherals(kind: String?): List<Peripheral> = peripheralLister(kind)
        override val network: NetworkAccess = this@ScriptRunHandle.networkAccess
        override val events: ScriptEventQueue = this@ScriptRunHandle.eventQueue
    }
}
