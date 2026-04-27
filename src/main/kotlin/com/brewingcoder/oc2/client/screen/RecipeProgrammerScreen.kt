package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.network.SaveRecipePatternPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Programmer GUI for [com.brewingcoder.oc2.item.RecipeCardItem]. Two layouts
 * driven by a client-side mode toggle:
 *
 *  - [RecipePattern.Mode.TABLE] — 3×3 ghost grid + auto-resolved RecipeManager
 *    output preview. The output slot at column 4 is read-only (display).
 *  - [RecipePattern.Mode.MACHINE] — 3×3 ghost grid (treated as a flat ingredient
 *    list), interactive output slot (player stamps the expected result), and a
 *    "Blocking" toggle button.
 *
 * Mode + blocking ride along on the [SaveRecipePatternPayload] — the server
 * reads them straight from the payload (its own menu has no flag state).
 * Output stamping is in the menu's [RecipeProgrammerMenu.ghostOutput] container,
 * which the server snapshots at save time.
 */
class RecipeProgrammerScreen(
    menu: RecipeProgrammerMenu,
    inv: Inventory,
    title: Component,
) : AbstractContainerScreen<RecipeProgrammerMenu>(menu, inv, title) {

    /** Client-only toggle state. Pre-loaded from the held card if it's already programmed. */
    private var mode: RecipePattern.Mode = RecipePattern.Mode.TABLE
    private var blocking: Boolean = false

    private lateinit var modeButton: Button
    private lateinit var blockingButton: Button

    init {
        imageWidth = 176
        imageHeight = 206
        inventoryLabelY = 110
        titleLabelY = 6

        // Pre-load mode/blocking from the held card, if any.
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player != null) {
            val held = player.getItemInHand(menu.hand)
            if (held.item is RecipeCardItem) {
                val existing = RecipeCardItem.pattern(held)
                if (existing != null) {
                    mode = existing.mode
                    blocking = existing.blocking
                }
            }
        }
    }

    override fun init() {
        super.init()
        addRenderableWidget(
            Button.builder(Component.literal("Clear")) { _ ->
                PacketDistributor.sendToServer(
                    SaveRecipePatternPayload(menu.containerId, clear = true)
                )
            }
                .pos(leftPos + 7, topPos + 18)
                .size(50, 18)
                .build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Save")) { _ ->
                PacketDistributor.sendToServer(
                    SaveRecipePatternPayload(
                        containerId = menu.containerId,
                        clear = false,
                        mode = mode.id,
                        blocking = blocking,
                    )
                )
                onClose()
            }
                .pos(leftPos + 7, topPos + 40)
                .size(50, 18)
                .build()
        )
        modeButton = addRenderableWidget(
            Button.builder(modeLabel()) { btn ->
                mode = if (mode == RecipePattern.Mode.TABLE) RecipePattern.Mode.MACHINE else RecipePattern.Mode.TABLE
                btn.message = modeLabel()
                blockingButton.active = (mode == RecipePattern.Mode.MACHINE)
            }
                .pos(leftPos + 7, topPos + 62)
                .size(50, 18)
                .build()
        )

        blockingButton = addRenderableWidget(
            Button.builder(blockingLabel()) { btn ->
                blocking = !blocking
                btn.message = blockingLabel()
            }
                .pos(leftPos + 7, topPos + 84)
                .size(50, 18)
                .build()
        )
        blockingButton.active = (mode == RecipePattern.Mode.MACHINE)
    }

    private fun modeLabel(): Component = Component.literal(
        if (mode == RecipePattern.Mode.TABLE) "Table" else "Machine"
    )

    private fun blockingLabel(): Component = Component.literal(
        if (blocking) "Block ON" else "Block OFF"
    )

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFE6E6E6.toInt(), false)
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFB8B8B8.toInt(), false)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202020.toInt())
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF383838.toInt())

        // 3×3 ghost grid wells.
        for (i in 0 until RecipePattern.SIZE) {
            val col = i % RecipePattern.WIDTH
            val row = i / RecipePattern.WIDTH
            val x = leftPos + RecipeProgrammerMenu.GRID_X + col * 18
            val y = topPos + RecipeProgrammerMenu.GRID_Y + row * 18
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF101010.toInt())
        }

        // Output well (column 4 next to the grid). Always visible — the underlying
        // ghost slot exists in both modes; in TABLE mode it's auto-filled from
        // RecipeManager preview, in MACHINE mode it's the manually-stamped result.
        val rx = leftPos + RecipeProgrammerMenu.GRID_X + 4 * 18
        val ry = topPos + RecipeProgrammerMenu.GRID_Y + 18
        graphics.fill(rx - 1, ry - 1, rx + 17, ry + 17, 0xFF101010.toInt())
        graphics.drawString(font, "->",
            leftPos + RecipeProgrammerMenu.GRID_X + 3 * 18 + 4,
            topPos + RecipeProgrammerMenu.GRID_Y + 18 + 4,
            0xFFC0C0C0.toInt(), false)

        // In TABLE mode the output well is read-only — we paint the resolved
        // recipe output on top of the ghost slot (the server-side ghost slot
        // stays empty in this mode, so there's no double-render). In MACHINE
        // mode we don't paint anything extra; vanilla slot rendering shows the
        // ghostOutput container's stack.
        if (mode == RecipePattern.Mode.TABLE) {
            val out = previewOutput()
            if (!out.isEmpty) {
                graphics.renderItem(out, rx, ry)
                graphics.renderItemDecorations(font, out, rx, ry)
            }
        }

        // Player inventory wells.
        for (row in 0 until 3) for (col in 0 until 9) {
            graphics.fill(leftPos + 7 + col * 18, topPos + 121 + row * 18,
                leftPos + 25 + col * 18, topPos + 139 + row * 18,
                0xFF101010.toInt())
        }
        for (col in 0 until 9) {
            graphics.fill(leftPos + 7 + col * 18, topPos + 179,
                leftPos + 25 + col * 18, topPos + 197,
                0xFF101010.toInt())
        }
    }

    /** Best-effort recipe-match against the local RecipeManager mirror. Table mode only. */
    private fun previewOutput(): ItemStack {
        val level = Minecraft.getInstance().level ?: return ItemStack.EMPTY
        val cells = (0 until RecipePattern.SIZE).map { menu.ghostGrid.getItem(it) }
        if (cells.all { it.isEmpty }) return ItemStack.EMPTY
        val input = CraftingInput.of(RecipePattern.WIDTH, RecipePattern.HEIGHT, cells)
        val match = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null)
            ?: return ItemStack.EMPTY
        return match.value().assemble(input, level.registryAccess())
    }

    companion object {
        @Suppress("unused")
        val ID: String = "${OpenComputers2.ID}.recipe_programmer"
    }
}
