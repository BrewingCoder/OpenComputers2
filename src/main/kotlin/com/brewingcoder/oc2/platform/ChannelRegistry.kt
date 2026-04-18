package com.brewingcoder.oc2.platform

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-world wifi channel registry. Computer block entities register themselves
 * here on load and unregister on remove. Future Adapter block entities will do
 * the same.
 *
 * Architectural rule (see docs/02-tier1-local.md and docs/08-never-list.md):
 *   **Discovery is REGISTRY-ONLY, NEVER scanning.** This object is the registry.
 *   Nothing in OC2 ever iterates loaded chunks looking for peripherals.
 *
 * v0 stores Computers only. v0.0.x will gain Adapter registration. R1 adds
 * range-aware in/out-of-range state computation. R2 adds Cloud Card opt-in
 * for global Control Plane access.
 *
 * Thread-safety: reads happen on server tick thread; mutations happen on the
 * BE lifecycle thread (also server). ConcurrentHashMap is used as belt-and-
 * suspenders against future async access.
 */
object ChannelRegistry {
    private val computersByChannel: MutableMap<String, MutableSet<ComputerBlockEntity>> =
        ConcurrentHashMap()

    fun register(computer: ComputerBlockEntity) {
        val members = computersByChannel.computeIfAbsent(computer.channelId) {
            ConcurrentHashMap.newKeySet()
        }
        members.add(computer)
        OpenComputers2.LOGGER.info(
            "registered computer @ {} on channel '{}' ({} now on this channel)",
            computer.blockPos, computer.channelId, members.size
        )
    }

    fun unregister(computer: ComputerBlockEntity) {
        val members = computersByChannel[computer.channelId] ?: return
        if (members.remove(computer)) {
            OpenComputers2.LOGGER.info(
                "unregistered computer @ {} from channel '{}' ({} remaining)",
                computer.blockPos, computer.channelId, members.size
            )
            if (members.isEmpty()) computersByChannel.remove(computer.channelId)
        }
    }

    /** Snapshot — safe to iterate without holding any internal lock. */
    fun computersOn(channelId: String): Set<ComputerBlockEntity> =
        computersByChannel[channelId]?.toSet() ?: emptySet()

    /** Snapshot of all known channels with at least one computer. */
    fun activeChannels(): Set<String> = computersByChannel.keys.toSet()

    /** Diagnostic: total computers registered across all channels. */
    fun totalComputers(): Int = computersByChannel.values.sumOf { it.size }
}
