package com.brewingcoder.oc2.item

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

/**
 * Creative-mode tab registry. v0 ships one tab, "OpenComputers2", containing
 * every block/item we register.
 *
 * Display names are localized via assets/oc2/lang/en_us.json — the
 * Component.translatable() calls below resolve to "itemGroup.oc2.main" etc.
 */
object ModTabs {
    val REGISTRY: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OpenComputers2.ID)

    val MAIN by REGISTRY.register("main") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.${OpenComputers2.ID}.main"))
            .icon { ItemStack(ModItems.COMPUTER) }
            .displayItems { _, output ->
                // every player-facing item OC2 ships goes here
                output.accept(ModItems.COMPUTER)
                output.accept(ModItems.MONITOR)
                output.accept(ModItems.ADAPTER)
                output.accept(ModItems.INVENTORY_PART)
                output.accept(ModItems.REDSTONE_PART)
                output.accept(ModItems.FLUID_PART)
                output.accept(ModItems.ENERGY_PART)
            }
            .build()
    }
}
