package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.network.TerminalOutputPayload
import net.minecraft.core.BlockPos

/**
 * Routes [TerminalOutputPayload]s from the client network handler to the
 * currently-open [ComputerScreen] for the same block position. Only one
 * screen can be open at a time on a single client, so this is a plain
 * single-subscriber map.
 *
 * Screens register on `init()` and unregister on `removed()`.
 *
 * **Pending buffer**: if a payload arrives while no screen is open for that
 * position, it's buffered (capped) until the player reopens the screen.
 * Without this, a long-running script's `print()` output is lost the moment
 * the player closes the screen to look at the monitor it's controlling.
 *
 * Per-position cap of [PENDING_CAP] payloads keeps memory bounded if a script
 * runs forever offline; oldest payloads drop first.
 */
object TerminalOutputDispatcher {

    private const val PENDING_CAP = 32

    private var active: Subscriber? = null
    private val pendingByPos: MutableMap<BlockPos, MutableList<TerminalOutputPayload>> = mutableMapOf()

    private data class Subscriber(val pos: BlockPos, val sink: (TerminalOutputPayload) -> Unit)

    fun register(pos: BlockPos, sink: (TerminalOutputPayload) -> Unit) {
        active = Subscriber(pos, sink)
        // Replay anything that arrived while no one was listening.
        pendingByPos.remove(pos)?.forEach(sink)
    }

    fun unregister(pos: BlockPos) {
        if (active?.pos == pos) active = null
    }

    fun dispatch(payload: TerminalOutputPayload) {
        val s = active
        if (s != null && s.pos == payload.pos) {
            s.sink(payload)
            return
        }
        // No subscriber → buffer for the next register() at this pos.
        val buf = pendingByPos.getOrPut(payload.pos) { mutableListOf() }
        buf.add(payload)
        while (buf.size > PENDING_CAP) buf.removeAt(0)
    }
}
