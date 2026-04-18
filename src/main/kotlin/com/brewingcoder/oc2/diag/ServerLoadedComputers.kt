package com.brewingcoder.oc2.diag

import java.util.concurrent.ConcurrentHashMap

/**
 * Server-thread JVM-static registry of every currently-loaded Computer block
 * entity. Exists for **diagnostics only** — the OC2-Debug companion mod (and
 * future tooling) reads from here via reflection so it doesn't need a hard
 * dependency on OC2.
 *
 * Purpose:
 *   - `list()` — enumerate computers a tester might want to inspect
 *   - `consoleOf(id)` — recent script output, for verifying scripts ran without
 *     having to open every computer's GUI
 *
 * Lifecycle:
 *   - [com.brewingcoder.oc2.block.ComputerBlockEntity.onLoad] calls [register]
 *     once an id is assigned
 *   - [com.brewingcoder.oc2.block.ComputerBlockEntity.setRemoved] calls
 *     [unregister]
 *   - Output sink is provided as a callable so callers can pull live data
 *     (no copy on every line)
 *
 * Stable surface — names + signatures are part of the diagnostic contract.
 * Renaming or repackaging breaks oc2-debug's reflection.
 */
object ServerLoadedComputers {

    /** Snapshot returned by [list]. Plain data — no MC types — so reflective callers are happy. */
    data class ComputerInfo(
        val id: Int,
        val dimension: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val channelId: String,
    )

    private val byId: ConcurrentHashMap<Int, Entry> = ConcurrentHashMap()

    private data class Entry(
        val info: ComputerInfo,
        val outputProvider: () -> List<String>,
        val writeFile: (path: String, content: String) -> Unit,
        val executeCommand: (cmd: String) -> List<String>,
    )

    /**
     * Register or update [id] with [info]. Provide callables for:
     *   - [outputProvider] — recent print() lines (drives [consoleOf])
     *   - [writeFile] — drop a file into the computer's mount (drives [writeFile])
     *   - [executeCommand] — run a shell command (drives [executeCommand])
     *
     * All three are invoked from oc2-debug tool calls. Implementations live on
     * the Computer BE so they reuse the production code paths.
     */
    fun register(
        info: ComputerInfo,
        outputProvider: () -> List<String>,
        writeFile: (String, String) -> Unit,
        executeCommand: (String) -> List<String>,
    ) {
        byId[info.id] = Entry(info, outputProvider, writeFile, executeCommand)
    }

    fun unregister(id: Int) {
        byId.remove(id)
    }

    /** Snapshot of every currently-loaded computer. Sorted by id for stable iteration. */
    fun list(): List<ComputerInfo> = byId.values.map { it.info }.sortedBy { it.id }

    /** Recent script output lines for [id], oldest first. Returns null if no such computer is loaded. */
    fun consoleOf(id: Int): List<String>? = byId[id]?.outputProvider?.invoke()

    /**
     * Write [content] to [path] (relative to the computer's mount root) on
     * computer [id]. Creates parent directories as needed. Throws if the
     * computer isn't loaded or the mount rejects the write.
     */
    fun writeFile(id: Int, path: String, content: String) {
        val entry = byId[id] ?: throw IllegalStateException("computer $id not loaded")
        entry.writeFile(path, content)
    }

    /**
     * Run [cmd] on the shell of computer [id]. Returns the synchronous output
     * lines (script-async output drains separately into [consoleOf]). Throws
     * if the computer isn't loaded.
     */
    fun executeCommand(id: Int, cmd: String): List<String> {
        val entry = byId[id] ?: throw IllegalStateException("computer $id not loaded")
        return entry.executeCommand(cmd)
    }

    /** Test/reset hook. */
    internal fun clearForTest() = byId.clear()
}
