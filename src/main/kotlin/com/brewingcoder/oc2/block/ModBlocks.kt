package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

/**
 * Block registry. v0 ships a single placeholder Computer block so we can verify
 * the mod loads, registers, and renders something in-world.
 *
 * Real R1 design (Computer + Adapter + Cartridges) lives in docs/ — not coded yet.
 */
object ModBlocks {
    val REGISTRY = DeferredRegister.createBlocks(OpenComputers2.ID)

    val COMPUTER by REGISTRY.registerSimpleBlock("computer") { ->
        BlockBehaviour.Properties.of()
            .strength(2.5f)
            .lightLevel { 6 }
    }
}
