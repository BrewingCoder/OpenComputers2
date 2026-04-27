package com.brewingcoder.oc2.item

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.AdapterPartsMenu
import com.brewingcoder.oc2.block.parts.CrafterMenu
import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Menu (formerly "container") type registry. Computer + Monitor settings are
 * direct [Screen]s with payloads (no AbstractContainerMenu), and so don't
 * appear here.
 *
 * Each menu uses [IMenuTypeExtension.create] (NeoForge variant) because the
 * open-menu packets carry custom payloads — vanilla's MenuType only takes
 * containerId + Inventory, no extra args.
 */
object ModMenus {
    val REGISTRY: DeferredRegister<MenuType<*>> =
        DeferredRegister.create(Registries.MENU, OpenComputers2.ID)

    val ADAPTER_PARTS: DeferredHolder<MenuType<*>, MenuType<AdapterPartsMenu>> =
        REGISTRY.register("adapter_parts") { ->
            IMenuTypeExtension.create(AdapterPartsMenu::fromNetwork)
        }

    /** Recipe Programmer GUI — opened by right-clicking a [com.brewingcoder.oc2.item.RecipeCardItem] in the air. */
    val RECIPE_PROGRAMMER: DeferredHolder<MenuType<*>, MenuType<RecipeProgrammerMenu>> =
        REGISTRY.register("recipe_programmer") { ->
            IMenuTypeExtension.create(RecipeProgrammerMenu::fromNetwork)
        }

    /** Crafter card-slot GUI — opened from the Part Settings "Recipes…" button. */
    val CRAFTER: DeferredHolder<MenuType<*>, MenuType<CrafterMenu>> =
        REGISTRY.register("crafter") { ->
            IMenuTypeExtension.create(CrafterMenu::fromNetwork)
        }
}
