package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.block.parts.PartItem
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Multi-part adapter — each face hosts one Part (Inventory/Fluid/Energy/Redstone).
 * Spawns an [AdapterBlockEntity] on placement.
 *
 * Player interactions:
 *   - Right-click face holding a [PartItem] → install part on that face
 *     (handled in [useItemOn]; falls back to no-op if face is occupied)
 *   - Empty-hand right-click on installed face → drops the part item (R2: GUI for label edit)
 *
 * [neighborChanged] forwards to the BE so capability-backed parts re-resolve
 * when the adjacent block changes (chest broken, machine swapped, etc.).
 */
class AdapterBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AdapterBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? AdapterBlockEntity
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        val partItem = stack.item as? PartItem
            ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        // Hit normal points OUT of the face the player clicked on; that's the
        // face we want to install onto.
        val face = hit.direction
        val type = partItem.partType
        val installed = be.installPart(face, type.create())
        if (!installed) {
            return ItemInteractionResult.SUCCESS  // face occupied, no-op
        }
        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide)
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

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    companion object {
        val CODEC: MapCodec<AdapterBlock> = simpleCodec(::AdapterBlock)
    }
}
