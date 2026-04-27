package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.item.ModItems
import net.minecraft.world.item.Item

/**
 * Reverse lookup from a [com.brewingcoder.oc2.platform.parts.Part.typeId] back
 * to the [PartItem] that installs it. Used by AdapterBlock when the player
 * sneak-removes a part — we need to know which item to drop.
 *
 * Single source of truth — adding a new part kind means adding one entry here
 * AND a registration in [com.brewingcoder.oc2.item.ModItems].
 */
object PartItems {
    fun itemFor(typeId: String): Item? = when (typeId) {
        InventoryPart.TYPE_ID -> ModItems.INVENTORY_PART
        RedstonePart.TYPE_ID  -> ModItems.REDSTONE_PART
        FluidPart.TYPE_ID     -> ModItems.FLUID_PART
        EnergyPart.TYPE_ID    -> ModItems.ENERGY_PART
        BlockPart.TYPE_ID     -> ModItems.BLOCK_PART
        BridgePart.TYPE_ID    -> ModItems.BRIDGE_PART
        CrafterPart.TYPE_ID   -> ModItems.CRAFTER_PART
        MachineCrafterPart.TYPE_ID -> ModItems.MACHINE_CRAFTER_PART
        else -> null
    }
}
