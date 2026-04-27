package com.brewingcoder.oc2.item

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * NeoForge 1.21 stack-component registry. Replaces the old NBT custom-data
 * pattern — every per-stack payload now flows through a typed [DataComponentType]
 * with a Codec for disk and a StreamCodec for the network.
 *
 * Adding a new component is one [DeferredRegister.register] call here plus a
 * stack accessor on the consuming Item class.
 */
object ModDataComponents {
    val REGISTRY: DeferredRegister.DataComponents =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, OpenComputers2.ID)

    /**
     * 3×3 recipe pattern stored on a Recipe Card stack. Empty cards have no
     * component (so the stack stays mergeable via vanilla equality); programmed
     * cards carry a [RecipePattern] payload and become non-stackable as a side
     * effect of vanilla's component-aware ItemStack.matches.
     */
    val RECIPE_PATTERN: DeferredHolder<DataComponentType<*>, DataComponentType<RecipePattern>> =
        REGISTRY.registerComponentType("recipe_pattern") { builder ->
            builder
                .persistent(RecipePattern.CODEC)
                .networkSynchronized(RecipePattern.STREAM_CODEC)
        }
}
