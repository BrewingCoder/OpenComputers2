package com.brewingcoder.oc2.compat.jei

import com.brewingcoder.oc2.OpenComputers2
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.registration.IRecipeTransferRegistration
import net.minecraft.resources.ResourceLocation

/**
 * JEI integration entry point. Registered automatically by JEI via the
 * [JeiPlugin] annotation when JEI is loaded; absent from runtime when JEI is
 * not present (the class loader skips it because none of our code references
 * it directly — it's discovered by JEI's annotation scanner).
 *
 * Currently registers a single recipe-transfer handler: vanilla
 * [RecipeTypes.CRAFTING] → our Recipe Card Programmer GUI. JEI's "Move Items"
 * button uses this handler to ghost-fill the programmer's 3×3 grid from any
 * crafting recipe in the JEI browser.
 */
@JeiPlugin
class OC2JeiPlugin : IModPlugin {

    override fun getPluginUid(): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "jei_plugin")

    override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
        registration.addRecipeTransferHandler(
            RecipeProgrammerTransferHandler(registration.transferHelper),
            RecipeTypes.CRAFTING,
        )
        registration.addUniversalRecipeTransferHandler(
            MachineRecipeTransferHandler()
        )
    }
}
