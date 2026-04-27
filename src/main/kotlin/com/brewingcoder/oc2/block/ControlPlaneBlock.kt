package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.control.ControlPlaneRegistry
import com.brewingcoder.oc2.storage.OC2ServerContext
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
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
        // If the placer isn't a real player (dispenser, command, etc.), reject —
        // ownership-keyed disk paths require a UUID anchor.
        val player = placer as? Player
        if (player == null) {
            rejectPlacement(level, pos, stack, null, "Control Plane requires a player owner; placement refused.")
            return
        }
        val serverLevel = level as? ServerLevel ?: return
        val registry = OC2ServerContext.get(serverLevel.server).controlPlanes
        val ownerId = player.uuid
        val location = ControlPlaneRegistry.Location(
            dimension = serverLevel.dimension().location().toString(),
            x = pos.x, y = pos.y, z = pos.z,
        )
        if (!registry.assign(ownerId, location)) {
            val existing = registry.locationFor(ownerId)
            rejectPlacement(
                level, pos, stack, player,
                "You already own a Control Plane at ${existing?.dimension} ${existing?.x},${existing?.y},${existing?.z}. " +
                    "Break it before placing a new one.",
            )
            return
        }
        level.setBlock(
            pos.above(),
            defaultBlockState().setValue(HALF, DoubleBlockHalf.UPPER),
            Block.UPDATE_ALL,
        )
    }

    /**
     * Roll back a placement: clear the lower half (which exists by the time
     * setPlacedBy fires), restore the item to the placer (if any), and surface
     * an explanation in chat. Suppresses drops so the lower-half block doesn't
     * pop out as loot in addition to the returned stack.
     */
    private fun rejectPlacement(
        level: Level,
        pos: BlockPos,
        stack: ItemStack,
        player: Player?,
        message: String,
    ) {
        level.setBlock(
            pos,
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
            Block.UPDATE_ALL or Block.UPDATE_SUPPRESS_DROPS,
        )
        if (player != null && !player.isCreative && !stack.isEmpty) {
            val returned = stack.copy()
            returned.count = 1
            if (!player.inventory.add(returned)) {
                player.drop(returned, false)
            }
        }
        player?.sendSystemMessage(Component.literal(message))
        OpenComputers2.LOGGER.info("control plane placement rejected @ {}: {}", pos, message)
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

    /**
     * Fires for every block removal — player breaks, /setblock, explosions,
     * piston pulls. We use it as the canonical "the Control Plane no longer
     * exists" signal so the registry stays consistent regardless of the
     * removal path.
     *
     * Only the LOWER half is registered (per [setPlacedBy]); the upper half's
     * [onRemove] is a no-op against the registry. Skips when [newState] is
     * still us (block-state changes that preserve identity).
     */
    override fun onRemove(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean,
    ) {
        if (newState.block !== this && state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            (level as? ServerLevel)?.let { serverLevel ->
                val registry = OC2ServerContext.get(serverLevel.server).controlPlanes
                val location = ControlPlaneRegistry.Location(
                    dimension = serverLevel.dimension().location().toString(),
                    x = pos.x, y = pos.y, z = pos.z,
                )
                registry.releaseAt(location)
            }
        }
        super.onRemove(state, level, pos, newState, isMoving)
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
