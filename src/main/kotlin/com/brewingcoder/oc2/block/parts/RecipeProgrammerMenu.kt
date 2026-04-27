package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Menu opened when the player right-clicks a [RecipeCardItem] in the air.
 *
 * Two modes (toggled client-side; carried into the [com.brewingcoder.oc2.network.SaveRecipePatternPayload]):
 *
 *  - [RecipePattern.Mode.TABLE] — 9 ghost slots arranged as a 3×3 grid for
 *    vanilla shaped/shapeless recipes; the result is auto-resolved at save time
 *    via [net.minecraft.world.item.crafting.RecipeManager].
 *  - [RecipePattern.Mode.MACHINE] — same 9 ghost slots treated as a flat
 *    ingredient list (slot order ignored); a 10th ghost slot stamps the manual
 *    output, plus a per-recipe "blocking" toggle.
 *
 * The 9 input ghosts live at indices 0..8; the optional manual output ghost
 * lives at [OUTPUT_GHOST_SLOT] (45) — placed AFTER the player inventory to
 * avoid renumbering vanilla's hotbar/main shuffles.
 */
class RecipeProgrammerMenu(
    containerId: Int,
    playerInventory: Inventory,
    val hand: InteractionHand,
) : AbstractContainerMenu(TYPE, containerId) {

    /** Backing for the 9 ghost INPUT slots. Items here are display-only; never extracted. */
    val ghostGrid: SimpleContainer = SimpleContainer(GHOST_COUNT)

    /** Backing for the manual-output ghost slot (machine-mode only; always present in the slot list). */
    val ghostOutput: SimpleContainer = SimpleContainer(1)

    init {
        // 0..8 — ghost grid, top-left to bottom-right (row-major).
        for (i in 0 until GHOST_COUNT) {
            val col = i % RecipePattern.WIDTH
            val row = i / RecipePattern.WIDTH
            addSlot(GhostSlot(ghostGrid, i, GRID_X + col * 18, GRID_Y + row * 18))
        }
        // Pre-load if the player's held stack is already programmed.
        val held = playerInventory.player.getItemHand(hand)
        if (held.item is RecipeCardItem) {
            val existing = RecipeCardItem.pattern(held)
            if (existing != null && !existing.isBlank) {
                for ((i, s) in existing.slots.withIndex()) ghostGrid.setItem(i, s.copy())
                ghostOutput.setItem(0, existing.output.copy())
            }
        }
        // 9..35 — player inventory main 3x9 (shifted down to clear the 4-button left rail)
        for (row in 0 until 3) for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 122 + row * 18))
        }
        // 36..44 — hotbar
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 180))
        }
        // 45 — manual-output ghost (machine mode only; always slotted to keep
        // the click router and snapshot path uniform).
        addSlot(GhostSlot(ghostOutput, 0, GRID_X + 4 * 18, GRID_Y + 18))
    }

    override fun stillValid(player: Player): Boolean {
        val held = player.getItemHand(hand)
        return !held.isEmpty && held.item is RecipeCardItem
    }

    /**
     * Custom click routing for the ghost slots: left-click copies the cursor
     * into the ghost slot without consuming, right-click clears.
     *
     * Input ghosts ([GhostSlot] in the 3×3 grid) keep their count-1 behavior —
     * the recipe-match path doesn't care about counts. The output ghost ([OUTPUT_GHOST_SLOT])
     * keeps the cursor's count so the player can stamp "x4 mini-coal" etc.
     */
    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (slotId in 0 until GHOST_COUNT) {
            when (button) {
                0 -> {
                    val cursor = carried.copy()
                    if (cursor.isEmpty) {
                        ghostGrid.setItem(slotId, ItemStack.EMPTY)
                    } else {
                        ghostGrid.setItem(slotId, cursor.copyWithCount(1))
                    }
                }
                1 -> ghostGrid.setItem(slotId, ItemStack.EMPTY)
            }
            return
        }
        if (slotId == OUTPUT_GHOST_SLOT) {
            when (button) {
                0 -> {
                    val cursor = carried.copy()
                    ghostOutput.setItem(0, if (cursor.isEmpty) ItemStack.EMPTY else cursor)
                }
                1 -> ghostOutput.setItem(0, ItemStack.EMPTY)
            }
            return
        }
        super.clicked(slotId, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, slotId: Int): ItemStack {
        // Disable shift-click on every ghost slot.
        if (slotId in 0 until GHOST_COUNT) return ItemStack.EMPTY
        if (slotId == OUTPUT_GHOST_SLOT) return ItemStack.EMPTY
        val slot = slots.getOrNull(slotId) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val invStart = GHOST_COUNT
        val mainStart = invStart
        val mainEnd = invStart + 27
        val hotbarEnd = invStart + 36
        val isHotbar = slotId >= mainEnd && slotId < hotbarEnd
        val src = slot.item
        val original = src.copy()
        val ok = if (isHotbar) {
            moveItemStackTo(src, mainStart, mainEnd, false)
        } else {
            moveItemStackTo(src, mainEnd, hotbarEnd, false)
        }
        if (!ok) return ItemStack.EMPTY
        if (src.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    /**
     * Snapshot the ghost grid as a [RecipePattern]. [mode] / [blocking] /
     * [fluidIn] come from the client-side toggle state (carried in the Save
     * payload); [output] comes from the manual-output ghost slot.
     */
    fun snapshotPattern(
        mode: RecipePattern.Mode,
        blocking: Boolean,
        fluidIn: RecipePattern.FluidSpec,
    ): RecipePattern {
        val list = (0 until GHOST_COUNT).map { ghostGrid.getItem(it).copy() }
        val out = ghostOutput.getItem(0).copy()
        return RecipePattern(
            slots = list,
            mode = mode,
            output = out,
            fluidIn = fluidIn,
            blocking = blocking,
        )
    }

    /** Slot whose only job is to render an [ItemStack] held in [container] without participating in vanilla pickup mechanics. */
    private class GhostSlot(container: SimpleContainer, idx: Int, x: Int, y: Int) : Slot(container, idx, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
        override fun getMaxStackSize(): Int = 64
    }

    companion object {
        const val GHOST_COUNT: Int = RecipePattern.SIZE  // 9
        const val GRID_X: Int = 62
        const val GRID_Y: Int = 18

        /**
         * Slot index of the manual-output ghost. After 9 input ghosts + 27
         * inventory + 9 hotbar = 45; lives at the tail of the slot list to
         * avoid renumbering the vanilla shuffle ranges.
         */
        const val OUTPUT_GHOST_SLOT: Int = GHOST_COUNT + 27 + 9  // 45

        val TYPE: MenuType<RecipeProgrammerMenu> by lazy {
            com.brewingcoder.oc2.item.ModMenus.RECIPE_PROGRAMMER.get()
        }

        fun fromNetwork(containerId: Int, inv: Inventory, buf: RegistryFriendlyByteBuf): RecipeProgrammerMenu {
            val hand = if (buf.readByte().toInt() == 0) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
            return RecipeProgrammerMenu(containerId, inv, hand)
        }
    }
}

/** Tiny extension for symmetry with [Player.getItemInHand]. Kotlin disambiguator. */
private fun Player.getItemHand(h: InteractionHand): ItemStack = getItemInHand(h)
