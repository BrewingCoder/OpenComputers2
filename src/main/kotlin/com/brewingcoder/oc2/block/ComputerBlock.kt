package com.brewingcoder.oc2.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * The Computer block. Spawns a [ComputerBlockEntity] when placed; ticks the BE
 * every server tick so the platform scheduler / channel registry stays current.
 *
 * Extends [BaseEntityBlock] for the ticker helper. Render shape is overridden
 * back to MODEL because BaseEntityBlock defaults to INVISIBLE (used by things
 * like Beacons that draw via TileEntityRenderer).
 */
class ComputerBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ComputerBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = createTickerHelper<ComputerBlockEntity, T>(
        blockEntityType,
        ModBlockEntities.COMPUTER.get(),
    ) { _, _, _, be -> be.tick() }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    companion object {
        val CODEC: MapCodec<ComputerBlock> = simpleCodec(::ComputerBlock)
    }
}
