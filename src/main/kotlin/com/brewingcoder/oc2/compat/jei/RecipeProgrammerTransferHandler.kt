package com.brewingcoder.oc2.compat.jei

import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.network.PopulateRecipePatternPayload
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Optional

/**
 * Maps a JEI crafting recipe view onto our [RecipeProgrammerMenu]'s 3×3 ghost
 * grid. JEI auto-renders a "Move Items" button when this handler is present
 * and the player has the programmer GUI open; clicking it sends a
 * [PopulateRecipePatternPayload] which the server applies to the menu's
 * ghost grid.
 *
 * The fill is "ghost" — no item ownership check, no inventory consumed. The
 * Save button on the screen is still the commit point; populating without
 * saving is a no-op once the screen closes.
 *
 * Slot mapping: JEI's `RecipeTypes.CRAFTING` lays out input slots in a 3×3
 * grid (shaped recipes pad empty corners; shapeless fills in display order).
 * We take the first 9 input slot views in the order JEI emits them and route
 * them to ghost cells 0..8 row-major.
 */
class RecipeProgrammerTransferHandler(
    private val helper: IRecipeTransferHandlerHelper,
) : IRecipeTransferHandler<RecipeProgrammerMenu, RecipeHolder<CraftingRecipe>> {

    override fun getContainerClass(): Class<out RecipeProgrammerMenu> =
        RecipeProgrammerMenu::class.java

    override fun getMenuType(): Optional<MenuType<RecipeProgrammerMenu>> =
        Optional.of(com.brewingcoder.oc2.item.ModMenus.RECIPE_PROGRAMMER.get())

    override fun getRecipeType(): RecipeType<RecipeHolder<CraftingRecipe>> = RecipeTypes.CRAFTING

    override fun transferRecipe(
        menu: RecipeProgrammerMenu,
        recipe: RecipeHolder<CraftingRecipe>,
        recipeSlots: IRecipeSlotsView,
        player: Player,
        maxTransfer: Boolean,
        doTransfer: Boolean,
    ): IRecipeTransferError? {
        if (!doTransfer) return null

        val inputViews = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)
        val cells = MutableList(RecipePattern.SIZE) { ItemStack.EMPTY }
        for ((idx, view) in inputViews.withIndex()) {
            if (idx >= RecipePattern.SIZE) break
            val displayed = view.getDisplayedIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY)
            cells[idx] = if (displayed.isEmpty) ItemStack.EMPTY else displayed.copyWithCount(1)
        }

        PacketDistributor.sendToServer(PopulateRecipePatternPayload(menu.containerId, cells))
        return null
    }
}
