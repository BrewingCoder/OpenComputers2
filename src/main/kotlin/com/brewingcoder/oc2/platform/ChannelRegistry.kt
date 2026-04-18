package com.brewingcoder.oc2.platform

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-world wifi channel registry. Tracks [ChannelRegistrant] members per
 * channel ID. This is the core of OC2's discovery system.
 *
 * Architectural rules (see docs/02-tier1-local.md and docs/08-never-list.md):
 *   - Discovery is REGISTRY-ONLY, NEVER scanning. Nothing in OC2 ever iterates
 *     loaded chunks looking for peripherals.
 *   - This object is the single source of truth for "what is on channel X."
 *
 * Engineering rules (see docs/11-engineering-rules.md):
 *   - Rule B: takes a [ChannelRegistrant] interface, NOT a concrete BlockEntity.
 *     Keeps the registry pure and unit-testable; production code wraps via
 *     the interface seam.
 *
 * Thread-safety: reads happen on server tick thread; mutations happen on the
 * BE lifecycle thread (also server). [ConcurrentHashMap] is belt-and-suspenders
 * against future async access (driver workers, scheduler threads).
 */
object ChannelRegistry {
    private val logger = LoggerFactory.getLogger(ChannelRegistry::class.java)

    private val byChannel: MutableMap<String, MutableSet<ChannelRegistrant>> =
        ConcurrentHashMap()

    fun register(registrant: ChannelRegistrant) {
        val members = byChannel.computeIfAbsent(registrant.channelId) {
            ConcurrentHashMap.newKeySet()
        }
        if (members.add(registrant)) {
            logger.info(
                "registered {} @ {} on channel '{}' ({} now on this channel)",
                registrant::class.simpleName, registrant.location, registrant.channelId, members.size,
            )
        }
    }

    fun unregister(registrant: ChannelRegistrant) {
        val members = byChannel[registrant.channelId] ?: return
        if (members.remove(registrant)) {
            logger.info(
                "unregistered {} @ {} from channel '{}' ({} remaining)",
                registrant::class.simpleName, registrant.location, registrant.channelId, members.size,
            )
            if (members.isEmpty()) byChannel.remove(registrant.channelId)
        }
    }

    /** Snapshot — safe to iterate without holding any internal lock. */
    fun membersOf(channelId: String): Set<ChannelRegistrant> =
        byChannel[channelId]?.toSet() ?: emptySet()

    /** Snapshot of all known channels with at least one registrant. */
    fun activeChannels(): Set<String> = byChannel.keys.toSet()

    /** Diagnostic: total registrants across all channels. */
    fun totalMembers(): Int = byChannel.values.sumOf { it.size }

    /** Test/reset hook — clears all state. Intended for unit tests only. */
    internal fun clearForTest() {
        byChannel.clear()
    }
}
