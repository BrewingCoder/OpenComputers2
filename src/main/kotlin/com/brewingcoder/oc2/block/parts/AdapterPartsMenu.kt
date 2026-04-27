package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.block.AdapterBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Adapter Parts GUI menu — 6 face slots (one per cube face) + the player's main
 * inventory + hotbar. Standard chest-like menu: vanilla cursor mechanics drive
 * everything, the [AdapterPartsContainer] translates slot writes into
 * installPart / removePart calls on the BE.
 *
 * Per-instance Part state (label, channelId, accessSide, options) does NOT
 * survive a face→face move — pickup goes through the cursor as a fresh
 * [PartItem], and re-placing creates a fresh [Part]. Configure the part again
 * after relocating it.
 */
class AdapterPartsMenu(
    containerId: Int,
    playerInventory: Inventory,
    val be: AdapterBlockEntity,
) : AbstractContainerMenu(TYPE, containerId) {

    private val partsContainer = AdapterPartsContainer(be)

    init {
        // Slots 0..5 — face slots in unfolded-cube cross layout.
        for ((i, face) in AdapterPartsContainer.SLOT_FACES.withIndex()) {
            val (x, y) = FACE_SLOT_POSITIONS.getValue(face)
            addSlot(DirectionalPartSlot(partsContainer, i, x, y, face))
        }

        // Slots 6..32 — player inventory main 3x9 at vanilla position
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
            }
        }
        // Slots 33..41 — player hotbar
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 142))
        }
    }

    override fun stillValid(player: Player): Boolean = partsContainer.stillValid(player)

    /**
     * Shift-click. Face-slot → first inventory slot that fits (sends the part
     * back to the player). Inventory → no-op for [PartItem] (drag onto a face
     * slot is the install path; "first empty face" guess is a UX gamble we
     * don't make). Inventory↔hotbar shuffles fall back to vanilla.
     */
    override fun quickMoveStack(player: Player, slotId: Int): ItemStack {
        val slot = slots.getOrNull(slotId) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY

        val isFaceSlot = slotId < AdapterPartsContainer.SLOT_FACES.size
        val invStart = AdapterPartsContainer.SLOT_FACES.size
        val invEnd = slots.size

        if (isFaceSlot) {
            val moved = slot.item.copy()
            if (!moveItemStackTo(moved, invStart, invEnd, true)) return ItemStack.EMPTY
            if (!moved.isEmpty) return ItemStack.EMPTY  // partial move; abort to avoid losing the part
            slot.set(ItemStack.EMPTY)  // triggers Container.setItem(slot, EMPTY) → removePart
            return ItemStack.EMPTY
        }

        // Inventory slot: PartItems don't auto-install (no face guess); other
        // items shuffle hotbar↔main like vanilla.
        if (slot.item.item is PartItem) return ItemStack.EMPTY

        val isHotbar = slotId >= invStart + 27
        val srcStack = slot.item
        val original = srcStack.copy()
        val ok = if (isHotbar) {
            moveItemStackTo(srcStack, invStart, invStart + 27, false)
        } else {
            moveItemStackTo(srcStack, invStart + 27, invEnd, false)
        }
        if (!ok) return ItemStack.EMPTY
        if (srcStack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    companion object {
        /**
         * Cross-shaped layout positions for the 6 face slots, in screen-local
         * coordinates. Used by both the menu (slot xy) and the screen (label
         * placement).
         *
         *        [UP]
         * [W]   [N]   [E]
         *       [S]
         *       [DN]
         */
        val FACE_SLOT_POSITIONS: Map<Direction, Pair<Int, Int>> = mapOf(
            Direction.UP    to (80 to  8),
            Direction.NORTH to (80 to 26),
            Direction.WEST  to (44 to 26),
            Direction.EAST  to (116 to 26),
            Direction.SOUTH to (80 to 44),
            Direction.DOWN  to (80 to 62),
        )

        val TYPE: MenuType<AdapterPartsMenu> by lazy {
            com.brewingcoder.oc2.item.ModMenus.ADAPTER_PARTS.get()
        }

        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): AdapterPartsMenu {
            val pos: BlockPos = buf.readBlockPos()
            val level: Level = inv.player.level()
            val be = level.getBlockEntity(pos) as? AdapterBlockEntity
                ?: error("AdapterPartsMenu opened on non-Adapter pos $pos")
            return AdapterPartsMenu(containerId, inv, be)
        }
    }
}
