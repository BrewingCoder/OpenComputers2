package com.brewingcoder.oc2.block.parts

import net.minecraft.core.Direction
import net.minecraft.world.Container
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Custom [Slot] for the Adapter Parts GUI. Capped at 1 stack and accepts only
 * [PartItem]s — vanilla cursor mechanics handle the rest, with the backing
 * [AdapterPartsContainer] translating slot writes into BE installPart / removePart calls.
 */
class DirectionalPartSlot(
    container: Container,
    slotIndex: Int,
    x: Int,
    y: Int,
    val face: Direction,
) : Slot(container, slotIndex, x, y) {

    override fun mayPlace(stack: ItemStack): Boolean = stack.item is PartItem

    override fun getMaxStackSize(): Int = 1
}
