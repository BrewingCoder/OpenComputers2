package com.brewingcoder.oc2.item

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * Player-held programmable card. Empty cards stack and can be inserted into a
 * Crafter Part's 18 card slots; programmed cards carry a [RecipePattern]
 * component and a tooltip line showing the recipe's intended output. Stackable
 * inside the Crafter slot but not in the player's inventory (vanilla
 * component-equality keeps two distinct patterns from merging).
 *
 * Right-clicking the card in the air opens a Programmer menu where the player
 * arranges the 3×3 ghost grid; saving consumes one empty card from the stack
 * and replaces it with a programmed card.
 *
 * Design note: the card stores ONLY the pattern — never a recipe ID. At craft
 * time the Crafter reconstructs a vanilla [net.minecraft.world.item.crafting.CraftingContainer]
 * from the pattern and asks the [net.minecraft.world.item.crafting.RecipeManager]
 * to match. This survives recipe-id drift across datapack reloads and naturally
 * handles shaped + shapeless recipes without a discriminator field.
 */
class RecipeCardItem(properties: Properties) : Item(properties) {

    /**
     * Right-click in the air → open the Programmer menu. We pass the
     * [InteractionHand] through the menu's network buffer so the server-side
     * handler knows which hand to consume the card from on Save.
     *
     * Cooperatively-opened: the screen pre-loads any existing pattern from the
     * stack (round-trip programming).
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide || player !is ServerPlayer) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }
        player.openMenu(
            object : MenuProvider {
                override fun getDisplayName(): Component =
                    Component.translatable("screen.${OpenComputers2.ID}.recipe_programmer")
                override fun createMenu(id: Int, inv: Inventory, p: Player): AbstractContainerMenu =
                    RecipeProgrammerMenu(id, inv, hand)
            }
        ) { buf -> buf.writeByte(if (hand == InteractionHand.MAIN_HAND) 0 else 1) }
        return InteractionResultHolder.success(stack)
    }

    override fun appendHoverText(
        stack: ItemStack,
        ctx: Item.TooltipContext,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val pattern = stack.get(ModDataComponents.RECIPE_PATTERN.get())
        if (pattern == null || pattern.isBlank) {
            tooltip.add(Component.translatable("item.oc2.recipe_card.tooltip.blank"))
            return
        }
        // Programmed: show a one-line "Programmed: NxItem" — the actual output
        // resolves at craft time, so the tooltip just hints at intent.
        tooltip.add(Component.translatable("item.oc2.recipe_card.tooltip.programmed"))
    }

    companion object {
        fun pattern(stack: ItemStack): RecipePattern? =
            stack.get(ModDataComponents.RECIPE_PATTERN.get())

        fun isProgrammed(stack: ItemStack): Boolean {
            val p = stack.get(ModDataComponents.RECIPE_PATTERN.get())
            return p != null && !p.isBlank
        }
    }
}
