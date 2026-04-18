package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.PartRegistry

/**
 * Mod-init registration of every [com.brewingcoder.oc2.platform.parts.PartType]
 * the adapter knows how to spawn. Called once from [com.brewingcoder.oc2.OpenComputers2.init]
 * before any world load — the AdapterBE's NBT-load path looks types up by id.
 *
 * Adding a new part is one line here + a new PartItem registration.
 */
object ModParts {
    fun register() {
        PartRegistry.register(InventoryPart.TYPE)
        PartRegistry.register(RedstonePart.TYPE)
        PartRegistry.register(FluidPart.TYPE)
        PartRegistry.register(EnergyPart.TYPE)
    }
}
