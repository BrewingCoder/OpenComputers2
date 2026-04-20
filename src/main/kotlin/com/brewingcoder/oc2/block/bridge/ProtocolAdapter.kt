package com.brewingcoder.oc2.block.bridge

import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * One protocol family the [com.brewingcoder.oc2.block.parts.BridgePart] knows
 * how to surface. Examples: CC's `IPeripheral`, ZeroCore's `IComputerPort`,
 * NeoForge capability fallback.
 *
 * The dispatcher walks adapters in registration order, first match wins.
 *
 * Adapters that import classes from optional mods MUST be loaded reflectively
 * by [BridgeDispatcher] only after a `ModList.isLoaded(modid)` guard — see the
 * BRIDGE-CLASS-ISOLATION comment in [BridgeDispatcher] for the JVM class-resolution gotcha.
 */
interface ProtocolAdapter {
    /** Stable id — `"cc"`, `"zerocore"`, `"caps"`. Used in [BridgePeripheral.protocol]. */
    val id: String

    /** Cheap predicate — implementations should NOT do heavy work here. */
    fun canHandle(be: BlockEntity, face: Direction): Boolean

    /**
     * Wrap the BE as a peripheral. Returns null when [canHandle] was true at
     * filter time but the underlying handle vanished by call time (rare —
     * connector lookup race). [name] is the bridge part's label.
     */
    fun wrap(be: BlockEntity, face: Direction, name: String): BridgePeripheral?
}
