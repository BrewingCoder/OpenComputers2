package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.CapabilityKey
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler

/**
 * Bridge between the platform-pure [CapabilityKey]s and concrete NeoForge
 * `BlockCapability`s. The Adapter BE uses [resolve] to translate when a Part
 * asks for a capability via [com.brewingcoder.oc2.platform.parts.PartHost.lookupCapability].
 *
 * Adding a new capability-backed part type is two lines: declare the key here,
 * resolve it in the `when` below.
 */
object PartCapabilityKeys {
    val ITEM: CapabilityKey<IItemHandler> = CapabilityKey("item")
    val FLUID: CapabilityKey<IFluidHandler> = CapabilityKey("fluid")
    val ENERGY: CapabilityKey<IEnergyStorage> = CapabilityKey("energy")

    /**
     * Map a [CapabilityKey] to NeoForge's typed `BlockCapability`. Returns
     * Pair(capability, expectsContext) — the second element documents that
     * BlockCapability lookups take a Direction context (the side facing
     * back at us from the adjacent block).
     */
    @Suppress("UNCHECKED_CAST")
    fun <C : Any> resolve(key: CapabilityKey<C>): net.neoforged.neoforge.capabilities.BlockCapability<C, net.minecraft.core.Direction?> = when (key) {
        ITEM -> Capabilities.ItemHandler.BLOCK as net.neoforged.neoforge.capabilities.BlockCapability<C, net.minecraft.core.Direction?>
        FLUID -> Capabilities.FluidHandler.BLOCK as net.neoforged.neoforge.capabilities.BlockCapability<C, net.minecraft.core.Direction?>
        ENERGY -> Capabilities.EnergyStorage.BLOCK as net.neoforged.neoforge.capabilities.BlockCapability<C, net.minecraft.core.Direction?>
        else -> error("unregistered CapabilityKey: $key")
    }
}
