package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.block.parts.AdapterPartsMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * GUI for the Adapter Parts menu — six face slots in an unfolded-cube cross
 * plus the player's inventory below. Drag a part item from inventory onto a
 * face slot to install on that face; click an installed part to pick it up
 * (preserving label/channel/options across the move). Shift-click an installed
 * part to send it back to your inventory.
 *
 * The menu's `clicked()` override (not vanilla's slot mechanics) drives every
 * face-slot interaction; this screen is just the visual.
 */
class AdapterPartsScreen(
    menu: AdapterPartsMenu,
    inv: Inventory,
    title: Component,
) : AbstractContainerScreen<AdapterPartsMenu>(menu, inv, title) {

    init {
        imageWidth = 176
        imageHeight = 198
        inventoryLabelY = imageHeight - 94
        titleLabelY = 4
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Solid panel: dark grey body with a 1px lighter outline. Matches the
        // look of the other OC2 screens (PartConfigScreen, MonitorConfigScreen)
        // without needing a custom 9-patch texture.
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202020.toInt())
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF383838.toInt())

        // Slot wells for the 6 face slots — darker squares behind each.
        // The direction letter is drawn INSIDE the well, centered. Vanilla
        // renders slot item icons in a later pass, so the letter is hidden
        // when a part is installed and visible when empty (a self-explaining
        // empty-state hint without any external labels colliding with
        // adjacent slots in the cross layout).
        for ((face, xy) in AdapterPartsMenu.FACE_SLOT_POSITIONS) {
            val (x, y) = xy
            graphics.fill(leftPos + x - 1, topPos + y - 1, leftPos + x + 17, topPos + y + 17, 0xFF101010.toInt())
            val letter = faceLetter(face)
            val w = font.width(letter)
            graphics.drawString(
                font, letter,
                leftPos + x + (16 - w) / 2,
                topPos + y + 4,
                0xFF606060.toInt(), false,
            )
        }

        // Player inventory background — 9×3 main + 9 hotbar at standard offsets.
        // Slot wells; vanilla's slot icons render on top automatically.
        for (row in 0 until 3) for (col in 0 until 9) {
            graphics.fill(leftPos + 7 + col * 18, topPos + 83 + row * 18,
                leftPos + 25 + col * 18, topPos + 101 + row * 18,
                0xFF101010.toInt())
        }
        for (col in 0 until 9) {
            graphics.fill(leftPos + 7 + col * 18, topPos + 141,
                leftPos + 25 + col * 18, topPos + 159,
                0xFF101010.toInt())
        }
    }

    private fun faceLetter(face: Direction): String = when (face) {
        Direction.UP -> "U"
        Direction.DOWN -> "D"
        Direction.NORTH -> "N"
        Direction.SOUTH -> "S"
        Direction.EAST -> "E"
        Direction.WEST -> "W"
    }
}
