package com.brewingcoder.oc2.item

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ModBlocks
import com.brewingcoder.oc2.block.parts.BlockPart
import com.brewingcoder.oc2.block.parts.BridgePart
import com.brewingcoder.oc2.block.parts.EnergyPart
import com.brewingcoder.oc2.block.parts.FluidPart
import com.brewingcoder.oc2.block.parts.InventoryPart
import com.brewingcoder.oc2.block.parts.PartItem
import com.brewingcoder.oc2.block.parts.RedstonePart
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

/**
 * Item registry. For each placeable Block we register a corresponding BlockItem
 * so the player can hold/give/place it. We use the helper
 * `registerSimpleBlockItem(blockHolder)` which handles the ID-on-Item-Properties
 * requirement that landed in MC 1.21.
 */
object ModItems {
    val REGISTRY: DeferredRegister.Items = DeferredRegister.createItems(OpenComputers2.ID)

    val COMPUTER_HOLDER: DeferredItem<BlockItem> =
        REGISTRY.registerSimpleBlockItem(ModBlocks.COMPUTER_HOLDER)
    val COMPUTER: Item by COMPUTER_HOLDER

    val MONITOR_HOLDER: DeferredItem<BlockItem> =
        REGISTRY.registerSimpleBlockItem(ModBlocks.MONITOR_HOLDER)
    val MONITOR: Item by MONITOR_HOLDER

    val ADAPTER_HOLDER: DeferredItem<BlockItem> =
        REGISTRY.registerSimpleBlockItem(ModBlocks.ADAPTER_HOLDER)
    val ADAPTER: Item by ADAPTER_HOLDER

    /** Inventory part — right-click an Adapter face holding this to install. */
    val INVENTORY_PART_HOLDER: DeferredItem<PartItem> = REGISTRY.register("inventory_part") { ->
        PartItem(InventoryPart.TYPE, Item.Properties())
    }
    val INVENTORY_PART: PartItem by INVENTORY_PART_HOLDER

    /** Redstone part — read input level / write output level on the adapter face. */
    val REDSTONE_PART_HOLDER: DeferredItem<PartItem> = REGISTRY.register("redstone_part") { ->
        PartItem(RedstonePart.TYPE, Item.Properties())
    }
    val REDSTONE_PART: PartItem by REDSTONE_PART_HOLDER

    /** Fluid part — read tanks / push & pull fluids on the adapter face. */
    val FLUID_PART_HOLDER: DeferredItem<PartItem> = REGISTRY.register("fluid_part") { ->
        PartItem(FluidPart.TYPE, Item.Properties())
    }
    val FLUID_PART: PartItem by FLUID_PART_HOLDER

    /** Energy part — read FE buffer / push & pull energy on the adapter face. */
    val ENERGY_PART_HOLDER: DeferredItem<PartItem> = REGISTRY.register("energy_part") { ->
        PartItem(EnergyPart.TYPE, Item.Properties())
    }
    val ENERGY_PART: PartItem by ENERGY_PART_HOLDER

    /** Block part — read adjacent block state / harvest the block into an inventory. */
    val BLOCK_PART_HOLDER: DeferredItem<PartItem> = REGISTRY.register("block_part") { ->
        PartItem(BlockPart.TYPE, Item.Properties())
    }
    val BLOCK_PART: PartItem by BLOCK_PART_HOLDER

    /** Bridge part — surfaces the adjacent BE's mod-specific scripting API. */
    val BRIDGE_PART_HOLDER: DeferredItem<PartItem> = REGISTRY.register("bridge_part") { ->
        PartItem(BridgePart.TYPE, Item.Properties())
    }
    val BRIDGE_PART: PartItem by BRIDGE_PART_HOLDER
}
