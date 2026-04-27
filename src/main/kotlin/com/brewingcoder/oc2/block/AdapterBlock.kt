package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.block.parts.PartItem
import com.brewingcoder.oc2.block.parts.PartItems
import com.brewingcoder.oc2.client.AdapterClientHandler
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * ID-style multi-part adapter. The block model is a small **cable hub** at the
 * center with thin **arms** extending toward each face that's *connected* —
 * either because a part is installed on that face, or because the adjacent
 * block is another adapter (visual-only auto-cabling).
 *
 * Six boolean properties — [CONN_NORTH] through [CONN_DOWN] — drive the model
 * via multipart blockstate JSON. Kind-specific colored part bumps are
 * rendered dynamically by [com.brewingcoder.oc2.client.AdapterRenderer]
 * (kept out of blockstate to avoid 5⁶ = 15625 variants).
 *
 * Voxel shape mirrors the model: core + per-arm boxes for connected faces.
 * Per-face hitboxes mean right-clicking an arm targets *that* face.
 *
 * Player interactions:
 *   - Right-click an arm holding a [PartItem] → install on that face
 *   - Right-click an arm empty-handed → (R2) drops the part item back
 *
 * [neighborChanged] forwards to the BE so capability-backed parts re-resolve
 * when the adjacent block changes.
 */
class AdapterBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(CONN_NORTH, false)
                .setValue(CONN_SOUTH, false)
                .setValue(CONN_EAST, false)
                .setValue(CONN_WEST, false)
                .setValue(CONN_UP, false)
                .setValue(CONN_DOWN, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(CONN_NORTH, CONN_SOUTH, CONN_EAST, CONN_WEST, CONN_UP, CONN_DOWN)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AdapterBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    /**
     * On placement, immediately set arms toward neighboring adapters so the
     * visual cabling appears without waiting for a neighbor change. The BE
     * still re-runs this on its `onLoad` for the same reason a Computer does
     * — the BE owns runtime mutations.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val level = context.level
        val pos = context.clickedPos
        var state = defaultBlockState()
        for (face in Direction.entries) {
            val neighbor = level.getBlockState(pos.relative(face))
            if (neighbor.block is AdapterBlock) {
                state = state.setValue(propertyFor(face), true)
            }
        }
        return state
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult {
        // Critical: PASS *before* the client-side short-circuit so empty-hand
        // (or any non-PartItem) right-clicks fall through to useWithoutItem.
        // Returning SUCCESS too early swallows the empty-hand path entirely.
        val partItem = stack.item as? PartItem
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (level.isClientSide) return ItemInteractionResult.sidedSuccess(true)
        val be = level.getBlockEntity(pos) as? AdapterBlockEntity
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        // Hit point relative to block origin tells us which arm got clicked —
        // hit.direction is the face *normal* of whatever sub-shape was hit, but
        // for an arm that may not be the face we want to install onto. Pick the
        // axis with the largest magnitude offset from the center.
        val face = faceFromHit(hit, pos)
            ?: hit.direction  // fallback to standard normal if hit is dead-center
        val installed = be.installPart(face, partItem.partType.create())
        if (!installed) {
            return ItemInteractionResult.SUCCESS
        }
        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide)
    }

    /**
     * Empty-hand right-click → open the Part Config GUI for the part on the
     * clicked arm. **Sneak + empty-hand right-click** → remove the part and
     * drop the part item back at the player. **Empty-hand right-click on an
     * empty face** → open the Adapter Parts GUI (drag-and-drop install /
     * re-arrange across all 6 faces).
     */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        val be = level.getBlockEntity(pos) as? AdapterBlockEntity
            ?: return InteractionResult.PASS
        val face = faceFromHit(hit, pos) ?: hit.direction
        val part = be.partOn(face)
        if (part == null) {
            // Empty face → parts GUI. Server-side opens the menu; client-side
            // is sidedSuccess so the click animation still plays.
            if (!player.isSecondaryUseActive && !level.isClientSide && player is net.minecraft.server.level.ServerPlayer) {
                player.openMenu(be) { buf -> buf.writeBlockPos(pos) }
                return InteractionResult.SUCCESS
            }
            return if (level.isClientSide) InteractionResult.sidedSuccess(true)
                   else InteractionResult.PASS
        }
        if (player.isSecondaryUseActive) {
            // Sneak: remove + spawn drop. Server only — client just consumes the click.
            // In survival, drops as an ItemEntity with velocity aimed at the player so
            // the pop is visibly attributable to the click. In creative we skip the drop
            // entirely — install doesn't shrink the source stack in creative, so refunding
            // the removal would net +1 part per install→remove cycle.
            if (!level.isClientSide) {
                val removed = be.removePart(face) ?: return InteractionResult.PASS
                val item = PartItems.itemFor(removed.typeId)
                if (item != null && !player.abilities.instabuild) {
                    val stack = net.minecraft.world.item.ItemStack(item)
                    // Spawn position: just outside the face we removed from, centered.
                    val origin = pos.relative(face)
                    val sx = origin.x + 0.5; val sy = origin.y + 0.5; val sz = origin.z + 0.5
                    // Aim toward the player's chest. Slight upward bias for a nice arc.
                    val dx = player.x - sx
                    val dy = (player.y + 1.0) - sy
                    val dz = player.z - sz
                    val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3)
                    val speed = 0.25
                    val vx = (dx / len) * speed
                    val vy = (dy / len) * speed + 0.1   // small lift
                    val vz = (dz / len) * speed
                    val ent = net.minecraft.world.entity.item.ItemEntity(level, sx, sy, sz, stack, vx, vy, vz)
                    ent.setDefaultPickUpDelay()         // ~10-tick delay (vanilla)
                    level.addFreshEntity(ent)
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        // Plain right-click → open config GUI on the client.
        if (level.isClientSide) {
            AdapterClientHandler.openPartConfigScreen(pos, face, part.typeId, part.label)
        }
        return InteractionResult.SUCCESS
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        if (level.isClientSide) return
        (level.getBlockEntity(pos) as? AdapterBlockEntity)?.onNeighborChanged()
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state)

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state)

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    /** Voxel shape = core + arm boxes for every connected face. Cached per-state. */
    private fun shapeFor(state: BlockState): VoxelShape {
        return SHAPE_CACHE.getOrPut(maskOf(state)) {
            var shape: VoxelShape = CORE_SHAPE
            for (face in Direction.entries) {
                if (state.getValue(propertyFor(face))) {
                    shape = Shapes.join(shape, ARM_SHAPES[face]!!, BooleanOp.OR)
                }
            }
            shape
        }
    }

    private fun maskOf(state: BlockState): Int {
        var m = 0
        for ((i, face) in Direction.entries.withIndex()) {
            if (state.getValue(propertyFor(face))) m = m or (1 shl i)
        }
        return m
    }

    /**
     * Decide which face the player meant when right-clicking an arm. Take the
     * coordinate offset from block-center; the axis with the largest absolute
     * offset is the "arm" the player aimed at. Returns null on dead-center hits
     * (rare — fallback to hit.direction handles it).
     */
    private fun faceFromHit(hit: BlockHitResult, pos: BlockPos): Direction? {
        val loc = hit.location
        val dx = loc.x - (pos.x + 0.5)
        val dy = loc.y - (pos.y + 0.5)
        val dz = loc.z - (pos.z + 0.5)
        val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy); val az = kotlin.math.abs(dz)
        if (ax < 0.01 && ay < 0.01 && az < 0.01) return null
        return when {
            ax >= ay && ax >= az -> if (dx > 0) Direction.EAST else Direction.WEST
            ay >= ax && ay >= az -> if (dy > 0) Direction.UP else Direction.DOWN
            else -> if (dz > 0) Direction.SOUTH else Direction.NORTH
        }
    }

    companion object {
        val CONN_NORTH: BooleanProperty = BooleanProperty.create("conn_north")
        val CONN_SOUTH: BooleanProperty = BooleanProperty.create("conn_south")
        val CONN_EAST: BooleanProperty = BooleanProperty.create("conn_east")
        val CONN_WEST: BooleanProperty = BooleanProperty.create("conn_west")
        val CONN_UP: BooleanProperty = BooleanProperty.create("conn_up")
        val CONN_DOWN: BooleanProperty = BooleanProperty.create("conn_down")

        val CODEC: MapCodec<AdapterBlock> = simpleCodec(::AdapterBlock)

        fun propertyFor(face: Direction): BooleanProperty = when (face) {
            Direction.NORTH -> CONN_NORTH
            Direction.SOUTH -> CONN_SOUTH
            Direction.EAST -> CONN_EAST
            Direction.WEST -> CONN_WEST
            Direction.UP -> CONN_UP
            Direction.DOWN -> CONN_DOWN
        }

        // Voxels: 6×6×6 core spans 5..11 in each axis. Arms are 4×4 cross-section
        // extending from core (5..11 → 11..16 toward each face).
        private val CORE_SHAPE: VoxelShape = Shapes.box(5/16.0, 5/16.0, 5/16.0, 11/16.0, 11/16.0, 11/16.0)
        private val ARM_SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.UP    to Shapes.box(6/16.0, 11/16.0, 6/16.0, 10/16.0, 16/16.0, 10/16.0),
            Direction.DOWN  to Shapes.box(6/16.0,  0/16.0, 6/16.0, 10/16.0,  5/16.0, 10/16.0),
            Direction.NORTH to Shapes.box(6/16.0,  6/16.0, 0/16.0, 10/16.0, 10/16.0,  5/16.0),
            Direction.SOUTH to Shapes.box(6/16.0,  6/16.0,11/16.0, 10/16.0, 10/16.0, 16/16.0),
            Direction.EAST  to Shapes.box(11/16.0, 6/16.0, 6/16.0, 16/16.0, 10/16.0, 10/16.0),
            Direction.WEST  to Shapes.box( 0/16.0, 6/16.0, 6/16.0,  5/16.0, 10/16.0, 10/16.0),
        )

        private val SHAPE_CACHE: MutableMap<Int, VoxelShape> = java.util.concurrent.ConcurrentHashMap()
    }
}
