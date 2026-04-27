package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * Tier-2 Control Plane block. 1×2 vertical occupant — lower half holds the
 * [ControlPlaneBlockEntity] (the actual VM); upper half is a dummy slot with
 * no BE. Pattern mirrors vanilla [net.minecraft.world.level.block.DoorBlock]
 * for the HALF property + paired-placement / paired-destroy logic.
 *
 * R1 minimum surface: places, ticks the VM on the lower half, breaks both
 * halves together, and surfaces a status message on right-click. GUI, terminal
 * screen, and config screen land in later commits.
 */
class ControlPlaneBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER))
    }

    override fun createBlockStateDefinition(
        builder: StateDefinition.Builder<Block, BlockState>,
    ) {
        builder.add(HALF)
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    // ------------------------------------------------------------------
    // Placement: only succeed if pos.above() is replaceable; the upper half
    // gets placed manually in setPlacedBy.
    // ------------------------------------------------------------------

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val pos = context.clickedPos
        val level = context.level
        if (pos.y >= level.maxBuildHeight - 1) return null
        if (!level.getBlockState(pos.above()).canBeReplaced(context)) return null
        return defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER)
    }

    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        stack: ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        level.setBlock(
            pos.above(),
            defaultBlockState().setValue(HALF, DoubleBlockHalf.UPPER),
            Block.UPDATE_ALL,
        )
    }

    // ------------------------------------------------------------------
    // Paired destroy: breaking either half breaks the other.
    // ------------------------------------------------------------------

    override fun playerWillDestroy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
    ): BlockState {
        if (!level.isClientSide && !player.isCreative) {
            preventCreativeDropFromOtherHalf(level, pos, state, player)
        }
        breakOtherHalf(level, pos, state, player.isCreative)
        return super.playerWillDestroy(level, pos, state, player)
    }

    private fun preventCreativeDropFromOtherHalf(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
    ) {
        val otherPos = otherHalfPos(pos, state) ?: return
        val other = level.getBlockState(otherPos)
        if (other.block === this && other.getValue(HALF) != state.getValue(HALF)) {
            level.setBlock(otherPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL or Block.UPDATE_SUPPRESS_DROPS)
        }
    }

    private fun breakOtherHalf(level: Level, pos: BlockPos, state: BlockState, dropless: Boolean) {
        val otherPos = otherHalfPos(pos, state) ?: return
        val other = level.getBlockState(otherPos)
        if (other.block !== this) return
        if (other.getValue(HALF) == state.getValue(HALF)) return
        val flags = if (dropless) Block.UPDATE_ALL or Block.UPDATE_SUPPRESS_DROPS else Block.UPDATE_ALL
        level.setBlock(otherPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags)
    }

    private fun otherHalfPos(pos: BlockPos, state: BlockState): BlockPos? = when (state.getValue(HALF)) {
        DoubleBlockHalf.LOWER -> pos.above()
        DoubleBlockHalf.UPPER -> pos.below()
        else -> null
    }

    // ------------------------------------------------------------------
    // Survival check: each half requires the other to remain.
    // ------------------------------------------------------------------

    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState {
        val expectedDir = if (state.getValue(HALF) == DoubleBlockHalf.LOWER) Direction.UP else Direction.DOWN
        if (direction != expectedDir) return state
        return if (neighborState.block === this && neighborState.getValue(HALF) != state.getValue(HALF)) {
            state
        } else {
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
        }
    }

    // ------------------------------------------------------------------
    // BE: only on the lower half.
    // ------------------------------------------------------------------

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) ControlPlaneBlockEntity(pos, state) else null

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) return null
        return createTickerHelper<ControlPlaneBlockEntity, T>(
            blockEntityType,
            ModBlockEntities.CONTROL_PLANE.get(),
        ) { _, _, _, be -> be.tick() }
    }

    // ------------------------------------------------------------------
    // Right-click: status surface (proof-of-life). Real GUI ships later.
    // ------------------------------------------------------------------

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val lowerPos = if (state.getValue(HALF) == DoubleBlockHalf.LOWER) pos else pos.below()
        val be = level.getBlockEntity(lowerPos) as? ControlPlaneBlockEntity
        val msg = be?.statusLine() ?: "Control Plane: BE not present"
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
        OpenComputers2.LOGGER.info("control plane @ {} status: {}", lowerPos, msg)
        return InteractionResult.CONSUME
    }

    companion object {
        val HALF: EnumProperty<DoubleBlockHalf> = EnumProperty.create("half", DoubleBlockHalf::class.java)
        val CODEC: MapCodec<ControlPlaneBlock> = simpleCodec(::ControlPlaneBlock)
    }
}
