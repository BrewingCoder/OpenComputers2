package com.brewingcoder.oc2.platform.os

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.ScriptHost
import com.brewingcoder.oc2.platform.script.ScriptRunHandle
import com.brewingcoder.oc2.platform.storage.WritableMount

/**
 * Tier-1 diagnostic shell — the "OS" that ships pre-installed on every Computer
 * block. Single command per line, no pipes/redirects/jobs (those belong to the
 * R1-week-2+ script-VM-hosted shell when Cobalt/Rhino land).
 *
 * Architecture: stateless [Shell] holds the command registry; per-computer
 * mutable state (cwd) lives in [ShellSession], owned by the BlockEntity.
 *
 * Rule D: this package is platform-pure. Commands take a [WritableMount] and a
 * [ShellOutput] sink — nothing from `net.minecraft.*`. Tests run without MC.
 */
class Shell(commands: Collection<ShellCommand>) {

    private val byName: Map<String, ShellCommand> = commands.associateBy { it.name }

    /** Snapshot of all registered commands. Used by `help`. */
    val all: Collection<ShellCommand> = commands.sortedBy { it.name }

    /** Tokenize, dispatch, capture output. Mutates [session.cwd] if the command does so. */
    fun execute(input: String, session: ShellSession): ShellResult {
        val tokens = Tokenizer.tokenize(input)
        if (tokens.isEmpty()) return ShellResult.empty()
        val name = tokens[0]
        val args = tokens.drop(1)
        val cmd = byName[name]
            ?: return ShellResult(listOf("$name: command not found. type 'help'"), false, 127)
        val collector = CollectingOutput()
        val ctx = ShellContext(session, collector)
        val exit = try {
            cmd.run(args, ctx)
        } catch (t: Throwable) {
            collector.println("$name: ${t.message ?: t::class.simpleName ?: "error"}")
            1
        }
        return ShellResult(collector.lines, collector.clearRequested, exit)
    }
}

/** A registered shell command. Implementations live in `platform.os.commands`. */
interface ShellCommand {
    val name: String
    val summary: String
    fun run(args: List<String>, ctx: ShellContext): Int
}

/** Where commands write their output. The shell installs a [CollectingOutput] for capture. */
interface ShellOutput {
    fun println(line: String)
    /** Wipe the terminal buffer (used by the `clear` command). */
    fun clear()
}

/**
 * Per-computer mutable state that survives across commands. Held by
 * `ComputerBlockEntity.shellSession`. cwd is the only mutable bit today;
 * environment vars and history will land here when needed.
 *
 * [peripheralFinder] is invoked each time a script asks `peripheral.find(kind)`.
 * Default returns null — useful for tests that don't need peripheral lookup.
 *
 * [scriptRunner] is the host service that spawns + tracks async script
 * executions. Provided by the BE; tests stub with a synchronous runner.
 */
class ShellSession(
    val mount: WritableMount,
    private val metadataProvider: () -> ShellMetadata,
    val peripheralFinder: (String) -> Peripheral? = { null },
    val peripheralLister: (String?) -> List<Peripheral> = { emptyList() },
    val networkAccess: NetworkAccess = NetworkAccess.NOOP,
    val scriptRunner: ScriptRunner = ScriptRunner.SYNCHRONOUS,
) {
    var cwd: String = ""

    /** Re-read on every access — channel/id can change at runtime. */
    val metadata: ShellMetadata get() = metadataProvider()
}

/**
 * Spawns + tracks async script executions for one computer. Installed on
 * [ShellSession]; the BE provides the production impl, tests use [SYNCHRONOUS].
 *
 *   - [start] returns a handle to a newly-running script (one at a time per BE)
 *   - [current] returns the active handle, or null if no script is running
 *   - [kill] aborts the current run; no-op if none
 */
interface ScriptRunner {
    /** Start a foreground script. AlreadyRunning if foreground busy. */
    fun start(
        host: ScriptHost,
        source: String,
        chunkName: String,
        mount: WritableMount,
        cwd: String,
        peripheralFinder: (String) -> Peripheral?,
        peripheralLister: (String?) -> List<Peripheral>,
        networkAccess: NetworkAccess = NetworkAccess.NOOP,
    ): StartResult

    /** Start a background script. Always succeeds (no concurrency limit). */
    fun startBackground(
        host: ScriptHost,
        source: String,
        chunkName: String,
        mount: WritableMount,
        cwd: String,
        peripheralFinder: (String) -> Peripheral?,
        peripheralLister: (String?) -> List<Peripheral>,
        networkAccess: NetworkAccess = NetworkAccess.NOOP,
    ): StartResult.Started = throw UnsupportedOperationException("background scripts not supported by this runner")

    /** Foreground only (or null). */
    fun current(): ScriptRunHandle?

    /** Foreground + every background, in pid order. */
    fun all(): List<ScriptRunHandle> = listOfNotNull(current())

    /** Kill foreground only (legacy `kill` shell command — no args). */
    fun kill(): Boolean

    /** Kill the script with this pid (foreground or background). */
    fun killByPid(pid: Int): Boolean = false

    /**
     * Kill every script (foreground + background). Used when the host BE is
     * removed/unloaded — keeps scripts from continuing to call into external
     * mods after their world context is gone, which surfaces as ugly
     * NullPointerException log spam from the target mod.
     */
    fun killAll() { all().forEach { if (!it.isDone()) it.kill() } }

    /** Promote a background script to foreground. Only allowed when no foreground is running. */
    fun moveToForeground(pid: Int): Boolean = false

    sealed interface StartResult {
        data class Started(val handle: ScriptRunHandle) : StartResult
        data class AlreadyRunning(val current: ScriptRunHandle) : StartResult
    }

    companion object {
        /** Synchronous fallback for tests — runs the script inline, returns the completed handle. */
        val SYNCHRONOUS: ScriptRunner = object : ScriptRunner {
            override fun start(
                host: ScriptHost, source: String, chunkName: String,
                mount: WritableMount, cwd: String,
                peripheralFinder: (String) -> Peripheral?,
                peripheralLister: (String?) -> List<Peripheral>,
                networkAccess: NetworkAccess,
            ): StartResult {
                val h = ScriptRunHandle(0, chunkName, host, source, mount, cwd, peripheralFinder, peripheralLister, networkAccess)
                h.start()
                while (!h.isDone()) Thread.sleep(1)
                return StartResult.Started(h)
            }
            override fun current(): ScriptRunHandle? = null
            override fun all(): List<ScriptRunHandle> = emptyList()
            override fun kill(): Boolean = false
            override fun killByPid(pid: Int): Boolean = false
            override fun moveToForeground(pid: Int): Boolean = false
        }
    }
}

/** Thin per-execution view passed to every command. */
class ShellContext(
    private val session: ShellSession,
    val out: ShellOutput,
) {
    val mount: WritableMount get() = session.mount
    val metadata: ShellMetadata get() = session.metadata
    val cwd: String get() = session.cwd
    val peripheralFinder: (String) -> Peripheral? get() = session.peripheralFinder
    val peripheralLister: (String?) -> List<Peripheral> get() = session.peripheralLister
    val networkAccess: NetworkAccess get() = session.networkAccess
    val scriptRunner: ScriptRunner get() = session.scriptRunner

    fun setCwd(newCwd: String) { session.cwd = newCwd }
}

/** Read-only view of the host computer's identity, exposed to commands like `id`. */
data class ShellMetadata(
    val computerId: Int,
    val channelId: String,
    val location: String,
)

/**
 * Result of one [Shell.execute] call. [lines] is the output to display;
 * [clearScreen] is true if the command wants the client terminal wiped first
 * (set by the `clear` command). [exitCode] is conventional (0 = ok).
 */
data class ShellResult(
    val lines: List<String>,
    val clearScreen: Boolean,
    val exitCode: Int,
) {
    companion object {
        fun empty(): ShellResult = ShellResult(emptyList(), false, 0)
    }
}

/** Internal sink: appends to a list, supports the clear-screen signal. */
internal class CollectingOutput : ShellOutput {
    private val buf = mutableListOf<String>()
    private var clearFlag = false

    val lines: List<String> get() = buf
    val clearRequested: Boolean get() = clearFlag

    override fun println(line: String) { buf.add(line) }
    override fun clear() { clearFlag = true; buf.clear() }
}
