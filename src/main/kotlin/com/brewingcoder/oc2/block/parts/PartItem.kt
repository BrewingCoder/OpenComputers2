package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.PartType
import net.minecraft.world.item.Item

/**
 * Crafted item that installs a specific [PartType] onto an Adapter face.
 * Subclasses are trivial — one per kind:
 *
 * ```kotlin
 * class InventoryPartItem : PartItem(InventoryPart.TYPE, Properties())
 * ```
 *
 * The right-click → install flow lives on [com.brewingcoder.oc2.block.AdapterBlock.useItemOn]
 * which checks `stack.item is PartItem`. Adding a new part type means adding
 * a PartItem subclass + a registry entry in `ModItems`.
 */
open class PartItem(
    val partType: PartType,
    properties: Properties,
) : Item(properties)
