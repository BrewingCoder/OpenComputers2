package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.CrafterMenu
import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeType

/**
 * Slot view over the [CrafterMenu]'s 18 card slots (2 rows × 9). Layout sits
 * inside the standard 176×166 chest-style panel: cards at y=18..54, the part
 * label at y=58, then the player's main inventory + hotbar at vanilla y=84+.
 *
 * Card slot rendering is special — when a slot holds a programmed
 * [RecipeCardItem], the slot displays the recipe's RESULT item icon in place
 * of the card, and hovering it shows ingredients + result instead of the
 * generic card tooltip. Cards in the player inventory below render normally.
 */
class CrafterScreen(
    menu: CrafterMenu,
    inv: Inventory,
    title: Component,
) : AbstractContainerScreen<CrafterMenu>(menu, inv, title) {

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = imageHeight - 94
        titleLabelY = 6
    }

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

        for (row in 0 until 2) for (col in 0 until 9) {
            val x = leftPos + 8 + col * 18
            val y = topPos + 18 + row * 18
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF101010.toInt())
        }
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

    /**
     * Substitute the card icon with the recipe's result icon for crafter card
     * slots that hold a programmed card. Other slots fall through to vanilla.
     */
    override fun renderSlot(graphics: GuiGraphics, slot: Slot) {
        if (slot.index in 0 until CrafterMenu.CARD_COUNT) {
            val result = recipeResult(slot.item)
            if (!result.isEmpty) {
                graphics.pose().pushPose()
                graphics.pose().translate(0f, 0f, 100f)
                graphics.renderItem(result, slot.x, slot.y)
                graphics.renderItemDecorations(font, result, slot.x, slot.y, null)
                graphics.pose().popPose()
                return
            }
        }
        super.renderSlot(graphics, slot)
    }

    /**
     * Custom tooltip on programmed cards in the card grid: header = result
     * item, then ingredient list grouped by item with counts. Cards in the
     * player inventory or unprogrammed cards fall through to the vanilla
     * "Recipe Card / Programmed" tooltip.
     */
    override fun getTooltipFromContainerItem(stack: ItemStack): MutableList<Component> {
        val hovered = hoveredSlot
        if (hovered != null && hovered.index in 0 until CrafterMenu.CARD_COUNT && stack.item is RecipeCardItem) {
            val pattern = RecipeCardItem.pattern(stack)
            if (pattern != null && !pattern.isBlank) {
                return buildRecipeTooltip(pattern)
            }
        }
        return super.getTooltipFromContainerItem(stack)
    }

    private fun recipeResult(stack: ItemStack): ItemStack {
        if (stack.isEmpty || stack.item !is RecipeCardItem) return ItemStack.EMPTY
        val pattern = RecipeCardItem.pattern(stack) ?: return ItemStack.EMPTY
        if (pattern.isBlank) return ItemStack.EMPTY
        // Machine cards stamp their own output; no vanilla RecipeManager lookup.
        if (pattern.mode == RecipePattern.Mode.MACHINE) return pattern.output.copy()
        val match = matchRecipe(pattern) ?: return ItemStack.EMPTY
        val level = Minecraft.getInstance().level ?: return ItemStack.EMPTY
        val input = CraftingInput.of(RecipePattern.WIDTH, RecipePattern.HEIGHT, pattern.slots)
        return match.value().assemble(input, level.registryAccess())
    }

    private fun matchRecipe(pattern: RecipePattern): RecipeHolder<CraftingRecipe>? {
        val level = Minecraft.getInstance().level ?: return null
        val input = CraftingInput.of(RecipePattern.WIDTH, RecipePattern.HEIGHT, pattern.slots)
        return level.recipeManager.getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null)
    }

    private fun buildRecipeTooltip(pattern: RecipePattern): MutableList<Component> {
        val out = mutableListOf<Component>()
        val result: ItemStack = when (pattern.mode) {
            RecipePattern.Mode.MACHINE -> pattern.output
            RecipePattern.Mode.TABLE -> {
                val match = matchRecipe(pattern)
                if (match == null) {
                    out.add(Component.translatable("item.oc2.recipe_card").withStyle(ChatFormatting.WHITE))
                    out.add(Component.translatable("item.oc2.recipe_card.tooltip.unmatched").withStyle(ChatFormatting.RED))
                    return out
                }
                val level = Minecraft.getInstance().level ?: return out
                val input = CraftingInput.of(RecipePattern.WIDTH, RecipePattern.HEIGHT, pattern.slots)
                match.value().assemble(input, level.registryAccess())
            }
        }

        if (result.isEmpty) {
            out.add(Component.translatable("item.oc2.recipe_card").withStyle(ChatFormatting.WHITE))
            out.add(Component.translatable("item.oc2.recipe_card.tooltip.unmatched").withStyle(ChatFormatting.RED))
            return out
        }

        out.add(result.hoverName.copy().withStyle(ChatFormatting.WHITE))
        out.add(Component.translatable("item.oc2.recipe_card.tooltip.result_line", result.count, result.hoverName)
            .withStyle(ChatFormatting.GRAY))
        out.add(Component.translatable("item.oc2.recipe_card.tooltip.ingredients_header").withStyle(ChatFormatting.GRAY))
        val grouped = LinkedHashMap<Item, Int>()
        for (cell in pattern.slots) {
            if (cell.isEmpty) continue
            grouped.merge(cell.item, 1, Int::plus)
        }
        for ((item, count) in grouped) {
            out.add(Component.translatable(
                "item.oc2.recipe_card.tooltip.ingredient_line",
                count,
                ItemStack(item).hoverName,
            ).withStyle(ChatFormatting.DARK_GRAY))
        }
        if (pattern.mode == RecipePattern.Mode.MACHINE && !pattern.fluidIn.isEmpty) {
            out.add(Component.literal("Fluid: ${pattern.fluidIn.mB} mB ${pattern.fluidIn.id}")
                .withStyle(ChatFormatting.DARK_GRAY))
        }
        return out
    }

    companion object {
        @Suppress("unused")
        val ID: String = "${OpenComputers2.ID}.crafter"
    }
}
