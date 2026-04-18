package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.CapabilityBackedPart
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.items.IItemHandler

/**
 * Inventory part — wraps the adjacent block's `IItemHandler` capability and
 * exposes it as an [InventoryPeripheral] to scripts. Reads slot contents,
 * pushes/pulls items between this and other inventory peripherals.
 *
 * Thin: every actual item move goes through `IItemHandler` extract/insert,
 * which handles slot capacity, item compat, and side-aware filtering.
 *
 * Per-slot conventions: scripts use **1-indexed** slot numbers (Lua/CC:T
 * tradition). Internally we translate to 0-indexed for `IItemHandler`.
 */
class InventoryPart : CapabilityBackedPart<IItemHandler>(TYPE_ID, PartCapabilityKeys.ITEM) {

    override fun wrapAsPeripheral(cap: IItemHandler): Peripheral = Wrapper(cap, label)

    /** [InventoryPeripheral] implementation backed by a live IItemHandler. */
    private class Wrapper(
        private val handler: IItemHandler,
        override val name: String,
    ) : InventoryPeripheral {
        override fun size(): Int = handler.slots

        override fun getItem(slot: Int): ItemSnapshot? {
            val idx = slot - 1
            if (idx !in 0 until handler.slots) return null
            return snapshot(handler.getStackInSlot(idx))
        }

        override fun list(): List<ItemSnapshot?> =
            (0 until handler.slots).map { snapshot(handler.getStackInSlot(it)) }

        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int {
            val src = slot - 1
            if (src !in 0 until handler.slots) return 0
            val toMove = handler.extractItem(src, count.coerceAtLeast(0), /* simulate = */ true)
            if (toMove.isEmpty) return 0
            val targetH = (target as? Wrapper)?.handler ?: return 0
            // Try the requested slot first; fall back to "first slot that fits"
            val movedStack = if (targetSlot != null) {
                tryInsertAt(targetH, targetSlot - 1, toMove)
            } else {
                tryInsertAny(targetH, toMove)
            }
            val actuallyMoved = toMove.count - movedStack.count
            if (actuallyMoved > 0) handler.extractItem(src, actuallyMoved, /* simulate = */ false)
            return actuallyMoved
        }

        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int {
            val srcH = (source as? Wrapper)?.handler ?: return 0
            val src = slot - 1
            if (src !in 0 until srcH.slots) return 0
            val toMove = srcH.extractItem(src, count.coerceAtLeast(0), /* simulate = */ true)
            if (toMove.isEmpty) return 0
            val movedStack = if (targetSlot != null) {
                tryInsertAt(handler, targetSlot - 1, toMove)
            } else {
                tryInsertAny(handler, toMove)
            }
            val actuallyMoved = toMove.count - movedStack.count
            if (actuallyMoved > 0) srcH.extractItem(src, actuallyMoved, /* simulate = */ false)
            return actuallyMoved
        }

        override fun find(itemId: String): Int {
            for (i in 0 until handler.slots) {
                val s = handler.getStackInSlot(i)
                if (s.isEmpty) continue
                if (idFor(s) == itemId) return i + 1
            }
            return -1
        }

        private fun tryInsertAt(h: IItemHandler, idx: Int, stack: ItemStack): ItemStack {
            if (idx !in 0 until h.slots) return stack
            return h.insertItem(idx, stack, /* simulate = */ false)
        }

        private fun tryInsertAny(h: IItemHandler, stack: ItemStack): ItemStack {
            var remainder = stack
            for (i in 0 until h.slots) {
                if (remainder.isEmpty) break
                remainder = h.insertItem(i, remainder, /* simulate = */ false)
            }
            return remainder
        }

        private fun snapshot(stack: ItemStack): ItemSnapshot? {
            if (stack.isEmpty) return null
            return ItemSnapshot(idFor(stack), stack.count)
        }

        private fun idFor(stack: ItemStack): String =
            BuiltInRegistries.ITEM.getKey(stack.item).toString()
    }

    companion object {
        const val TYPE_ID: String = "inventory"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = InventoryPart()
        }
    }
}
