package com.brewingcoder.oc2.compat.jei

import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.network.PopulateRecipePatternPayload
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Optional

/**
 * JEI universal handler for the Recipe Programmer in MACHINE mode. Accepts ANY
 * recipe type — smelting / blasting / smoking / Mek processors / Create kinetic
 * machines / cooking — by reading the JEI slot views' [RecipeIngredientRole] roles
 * rather than the recipe's concrete class.
 *
 * Slot mapping:
 *  - Up to 9 [RecipeIngredientRole.INPUT] item views → ghost grid cells 0..8
 *  - First non-empty [RecipeIngredientRole.OUTPUT] item view → ghost output slot
 *
 * Vanilla shaped/shapeless crafting still goes through [RecipeProgrammerTransferHandler]
 * because typed handlers win over universal ones in JEI's dispatch — that one keeps
 * its tighter 3×3 layout semantics. This universal handler picks up everything else.
 *
 * Fluid inputs (Mek "advanced infuser" style) aren't routed yet — those need a
 * fluid-input UI on the screen first; tracked as a follow-up.
 */
class MachineRecipeTransferHandler : IUniversalRecipeTransferHandler<RecipeProgrammerMenu> {

    override fun getContainerClass(): Class<out RecipeProgrammerMenu> =
        RecipeProgrammerMenu::class.java

    override fun getMenuType(): Optional<MenuType<RecipeProgrammerMenu>> =
        Optional.of(com.brewingcoder.oc2.item.ModMenus.RECIPE_PROGRAMMER.get())

    override fun transferRecipe(
        menu: RecipeProgrammerMenu,
        recipe: Any,
        recipeSlots: IRecipeSlotsView,
        player: Player,
        maxTransfer: Boolean,
        doTransfer: Boolean,
    ): IRecipeTransferError? {
        // JEI dispatches typed handlers (e.g. vanilla CRAFTING) before universal,
        // so we only see non-crafting recipe types here.
        if (!doTransfer) return null

        val cells = MutableList(RecipePattern.SIZE) { ItemStack.EMPTY }
        val inputViews = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT)
        var idx = 0
        for (view in inputViews) {
            if (idx >= RecipePattern.SIZE) break
            val displayed = view.getDisplayedIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY)
            if (displayed.isEmpty) continue
            cells[idx] = displayed.copyWithCount(1)
            idx++
        }

        val outputViews = recipeSlots.getSlotViews(RecipeIngredientRole.OUTPUT)
        val output = outputViews
            .asSequence()
            .map { it.getDisplayedIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY) }
            .firstOrNull { !it.isEmpty }
            ?: ItemStack.EMPTY

        PacketDistributor.sendToServer(
            PopulateRecipePatternPayload(menu.containerId, cells, output.copy())
        )
        return null
    }
}
