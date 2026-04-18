package com.brewingcoder.oc2.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition

/**
 * The Computer block. Spawns a [ComputerBlockEntity] when placed; ticks the BE
 * every server tick so the platform scheduler / channel registry stays current.
 *
 * Has a horizontal FACING property — the front face (the OC server-grille texture)
 * always faces the player on placement. Rotates / mirrors with the world.
 *
 * Extends [BaseEntityBlock] for the ticker helper. Render shape is overridden
 * back to MODEL because BaseEntityBlock defaults to INVISIBLE.
 */
class ComputerBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState>) {
        builder.add(FACING)
    }

    /** Place with the front (north face of the model) pointing toward the player. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

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
        val FACING = HorizontalDirectionalBlock.FACING
        val CODEC: MapCodec<ComputerBlock> = simpleCodec(::ComputerBlock)
    }
}
