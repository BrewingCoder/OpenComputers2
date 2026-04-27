package com.brewingcoder.oc2.block.parts

import net.minecraft.world.SimpleContainer

/**
 * Marker interface for parts that hold a [SimpleContainer] of programmable
 * recipe cards. [CrafterPart] (vanilla 3×3 grid) and [MachineCrafterPart]
 * (machine-mode flat ingredient list) both qualify; the Part Settings
 * "Recipes…" launcher uses this to open a single shared 18-slot menu.
 */
interface HasRecipeCards {
    val cards: SimpleContainer
}
