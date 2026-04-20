package com.brewingcoder.oc2.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import kotlin.math.floor

/**
 * Monitor block — places in world, displays text on its front face. The face
 * direction is set on placement to match the player's facing.
 *
 * Multi-block: adjacent monitors of the same facing auto-merge into a
 * rectangular group (see [MonitorBlockEntity]). Non-rectangular layouts keep
 * each monitor as a 1×1 group. Wall-mount facings only for v0 (N/S/E/W);
 * ceiling/floor monitors land in M2.
 *
 * Has no GUI — interaction is via Lua/JS scripts on a Computer that finds
 * this monitor on its wifi channel.
 */
class MonitorBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    /** Face the player on placement — matches Computer block convention. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        MonitorBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    /**
     * Trigger a merge re-evaluation when a neighboring block changes — handles
     * both newly-placed adjacent monitors AND broken neighbors (which would
     * shrink/split a group).
     */
    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? MonitorBlockEntity ?: return
            be.requestGroupReevaluation()
        }
    }

    companion object {
        val FACING = HorizontalDirectionalBlock.FACING
        val CODEC: MapCodec<MonitorBlock> = simpleCodec(::MonitorBlock)

        /**
         * Bezel inset matching [com.brewingcoder.oc2.client.screen.MonitorRenderer.MARGIN_WORLD].
         * Kept in sync manually — both must reflect the same physical layout for touch
         * coordinates to land on the right cell. If you change one, change both.
         */
        const val MARGIN_WORLD = 0.04

        /**
         * Convert a world-space hit position on a monitor's front face to a (col, row)
         * character-cell coordinate inside the master's full group surface, or null
         * if the hit landed in the bezel margin.
         *
         * Mirrors the geometry derivation in MonitorRenderer so the cell a script
         * sees on touch is the same cell the player visually clicked on.
         */
        /**
         * Resolved touch coordinates inside the master's full group surface.
         * `col`/`row` are the legacy character-cell coordinate; `px`/`py` are
         * the HD pixel-grid coordinate (use these for graphical button hit-testing).
         */
        data class Hit(val col: Int, val row: Int, val px: Int, val py: Int)

        fun hitToCell(
            facing: Direction,
            master: MonitorBlockEntity,
            hit: net.minecraft.world.phys.Vec3,
        ): Hit? {
            val groupW = master.groupBlocksWide
            val groupH = master.groupBlocksTall
            val mp = master.blockPos
            val viewX: Double; val viewY: Double
            when (facing) {
                Direction.NORTH -> { viewX = (mp.x + groupW) - hit.x;  viewY = (mp.y + groupH) - hit.y }
                Direction.SOUTH -> { viewX = hit.x - mp.x;             viewY = (mp.y + groupH) - hit.y }
                Direction.EAST  -> { viewX = (mp.z + groupW) - hit.z;  viewY = (mp.y + groupH) - hit.y }
                Direction.WEST  -> { viewX = hit.z - mp.z;             viewY = (mp.y + groupH) - hit.y }
                else -> return null
            }
            val drawableW = groupW - 2.0 * MARGIN_WORLD
            val drawableH = groupH - 2.0 * MARGIN_WORLD
            val adjX = viewX - MARGIN_WORLD
            val adjY = viewY - MARGIN_WORLD
            if (adjX < 0 || adjY < 0 || adjX >= drawableW || adjY >= drawableH) return null
            val totalCols = groupW * MonitorBlockEntity.COLS_PER_BLOCK
            val totalRows = groupH * MonitorBlockEntity.ROWS_PER_BLOCK
            val col = floor(adjX / drawableW * totalCols).toInt().coerceIn(0, totalCols - 1)
            val row = floor(adjY / drawableH * totalRows).toInt().coerceIn(0, totalRows - 1)
            val pxW = totalCols * MonitorBlockEntity.PX_PER_CELL
            val pxH = totalRows * MonitorBlockEntity.PX_PER_CELL
            val px = floor(adjX / drawableW * pxW).toInt().coerceIn(0, pxW - 1)
            val py = floor(adjY / drawableH * pxH).toInt().coerceIn(0, pxH - 1)
            return Hit(col, row, px, py)
        }
    }
}
