package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * Block entity registry. One BE type per block-with-state.
 *
 * 1.21+ removed the public Builder API; we use NeoForge's convenience
 * constructor: BlockEntityType(supplier, validBlocks...).
 *
 * `::ComputerBlockEntity` is auto-converted to BlockEntityType.BlockEntitySupplier
 * (a SAM interface taking BlockPos + BlockState).
 */
object ModBlockEntities {
    val REGISTRY: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OpenComputers2.ID)

    val COMPUTER = REGISTRY.register("computer") { ->
        BlockEntityType.Builder
            .of(::ComputerBlockEntity, ModBlocks.COMPUTER)
            .build(null)
    }
}
