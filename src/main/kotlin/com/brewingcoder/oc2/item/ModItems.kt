package com.brewingcoder.oc2.item

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ModBlocks
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
}
