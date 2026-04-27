package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.block.AdapterBlockEntity
import com.brewingcoder.oc2.item.RecipeCardItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Slot menu over a [CrafterPart]'s 18 card slots, opened from the Part Settings
 * "Recipes…" button. Standard chest-style layout: 2 rows of 9 above the
 * player's main inventory + hotbar.
 *
 * Slots accept only [RecipeCardItem]s (one per slot — the part keeps cards
 * separate so each one is its own programmable address). Shift-click moves
 * cards back to the inventory; non-card stacks shuffle hotbar↔main like
 * vanilla.
 *
 * The cards container lives on the Part — vanilla menu sync streams
 * [CrafterPart.cards] writes to clients automatically as long as the menu is
 * open. Server-side authoritative state is the single source of truth.
 */
class CrafterMenu(
    containerId: Int,
    playerInventory: Inventory,
    val be: AdapterBlockEntity,
    val face: Direction,
) : AbstractContainerMenu(TYPE, containerId) {

    private val part: HasRecipeCards? = be.partOn(face) as? HasRecipeCards

    init {
        // Slots 0..17 — card slots (2 rows × 9 cols).
        val cards = part?.cards
        for (row in 0 until 2) for (col in 0 until 9) {
            val idx = col + row * 9
            val x = 8 + col * 18
            val y = 18 + row * 18
            if (cards != null) {
                addSlot(CardSlot(cards, idx, x, y))
            } else {
                // Defensive: no part → blank read-only slots so the menu doesn't
                // crash on a bad open. Player can't interact (mayPickup=false).
                addSlot(BlankSlot(idx, x, y))
            }
        }

        // Slots 18..44 — player inventory main 3x9
        for (row in 0 until 3) for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
        }
        // Slots 45..53 — hotbar
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 142))
        }
    }

    override fun stillValid(player: Player): Boolean {
        // BE removed or part swapped to a different kind → close.
        return part != null &&
            be.partOn(face) === part &&
            player.distanceToSqr(
                be.blockPos.x + 0.5, be.blockPos.y + 0.5, be.blockPos.z + 0.5,
            ) <= 64.0
    }

    override fun quickMoveStack(player: Player, slotId: Int): ItemStack {
        val slot = slots.getOrNull(slotId) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val src = slot.item
        val original = src.copy()

        val cardsEnd = CARD_COUNT
        val invStart = cardsEnd
        val mainEnd = invStart + 27
        val hotbarEnd = invStart + 36

        val ok = if (slotId < cardsEnd) {
            // Card slot → inventory.
            moveItemStackTo(src, invStart, hotbarEnd, true)
        } else {
            // Inventory → first matching empty card slot, but only RecipeCardItems.
            if (src.item !is RecipeCardItem) {
                val isHotbar = slotId >= mainEnd
                if (isHotbar) moveItemStackTo(src, invStart, mainEnd, false)
                else moveItemStackTo(src, mainEnd, hotbarEnd, false)
            } else {
                moveItemStackTo(src, 0, cardsEnd, false)
            }
        }
        if (!ok) return ItemStack.EMPTY
        if (src.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    /** Slot accepting only [RecipeCardItem]s, max stack 64 (vanilla card stacking). */
    private class CardSlot(container: net.minecraft.world.SimpleContainer, idx: Int, x: Int, y: Int) :
        Slot(container, idx, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.item is RecipeCardItem
    }

    /** No-op slot used when the menu opens without a backing part (BE went away). */
    private class BlankSlot(idx: Int, x: Int, y: Int) :
        Slot(net.minecraft.world.SimpleContainer(1), idx % 1, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
    }

    companion object {
        // Both Crafter and Machine Crafter parts use 18 card slots; this just
        // pins the menu's slot count to that shared shape.
        const val CARD_COUNT: Int = CrafterPart.SLOT_COUNT  // 18

        val TYPE: MenuType<CrafterMenu> by lazy {
            com.brewingcoder.oc2.item.ModMenus.CRAFTER.get()
        }

        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): CrafterMenu {
            val pos: BlockPos = buf.readBlockPos()
            val face: Direction = Direction.from3DDataValue(buf.readByte().toInt())
            val be = inv.player.level().getBlockEntity(pos) as? AdapterBlockEntity
                ?: error("CrafterMenu opened on non-Adapter pos $pos")
            return CrafterMenu(containerId, inv, be, face)
        }
    }
}
