package com.brewingcoder.oc2.event

import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.script.ScriptEvent

/**
 * Routes [ScriptEvent]s into the right Computer's running script. Event
 * sources (monitor BE on touch, network inbox on deliver, etc.) call
 * [fireToChannel] — the dispatch finds every Computer on that channel via
 * [ChannelRegistry] and offers the event into each running script's queue.
 *
 * Idle computers (no script running) silently drop the event. That's by
 * design — events are *push notifications* to active code; nothing to do
 * when no code is listening.
 */
object EventDispatch {
    /**
     * Fire [event] to every Computer on [channelId] that has a running script.
     * Returns the number of scripts that received it.
     */
    fun fireToChannel(channelId: String, event: ScriptEvent): Int {
        var count = 0
        for (r in ChannelRegistry.listOnChannel(channelId, "computer")) {
            val be = r as? ComputerBlockEntity ?: continue
            val q = be.runningScriptEvents() ?: continue
            q.offer(event)
            count++
        }
        return count
    }

    /** Fire [event] to a specific Computer by id. Returns true if delivered. */
    fun fireToComputerId(computerId: Int, event: ScriptEvent): Boolean {
        // Walk every channel — small price for not maintaining a separate id→BE map
        // (we already have ChannelRegistry; ServerLoadedComputers also exists but
        // its outputProvider lambda doesn't expose the BE).
        for (channel in ChannelRegistry.activeChannels()) {
            for (r in ChannelRegistry.listOnChannel(channel, "computer")) {
                val be = r as? ComputerBlockEntity ?: continue
                if (be.computerId != computerId) continue
                val q = be.runningScriptEvents() ?: return false
                q.offer(event)
                return true
            }
        }
        return false
    }
}
