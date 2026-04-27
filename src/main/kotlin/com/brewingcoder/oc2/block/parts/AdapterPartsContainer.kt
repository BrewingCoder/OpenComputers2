package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.block.AdapterBlockEntity
import net.minecraft.core.Direction
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * [Container] backed by an [AdapterBlockEntity]'s face→Part map. Each slot
 * corresponds 1:1 with a face from [SLOT_FACES]; [getItem] synthesizes a
 * representative [PartItem] stack for whatever is installed on that face.
 *
 * Writes go straight through to the BE: [setItem] with a [PartItem] calls
 * [AdapterBlockEntity.installPart], [setItem] with empty (and [removeItem])
 * calls [AdapterBlockEntity.removePart]. Vanilla cursor mechanics
 * (place / pickup / swap / split) work on these slots just like a chest's,
 * with one caveat — face↔face moves resolve as remove+install through the
 * cursor, so per-instance Part state (label, channelId, options) does NOT
 * survive a move. Configure the part again after relocating it.
 */
class AdapterPartsContainer(
    private val be: AdapterBlockEntity,
) : Container {

    override fun getContainerSize(): Int = SLOT_FACES.size

    override fun isEmpty(): Boolean = SLOT_FACES.all { be.partOn(it) == null }

    override fun getItem(slot: Int): ItemStack {
        val face = SLOT_FACES.getOrNull(slot) ?: return ItemStack.EMPTY
        val part = be.partOn(face) ?: return ItemStack.EMPTY
        val item = PartItems.itemFor(part.typeId) ?: return ItemStack.EMPTY
        return ItemStack(item, 1)
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        if (amount <= 0) return ItemStack.EMPTY
        val face = SLOT_FACES.getOrNull(slot) ?: return ItemStack.EMPTY
        val current = getItem(slot)
        if (current.isEmpty) return ItemStack.EMPTY
        be.removePart(face) ?: return ItemStack.EMPTY
        setChanged()
        return current
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val face = SLOT_FACES.getOrNull(slot) ?: return ItemStack.EMPTY
        val current = getItem(slot)
        if (current.isEmpty) return ItemStack.EMPTY
        be.removePart(face) ?: return ItemStack.EMPTY
        return current
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        val face = SLOT_FACES.getOrNull(slot) ?: return
        val occupied = be.partOn(face) != null

        if (stack.isEmpty) {
            if (occupied) {
                be.removePart(face)
                setChanged()
            }
            return
        }

        val partItem = stack.item as? PartItem ?: return
        // Vanilla's slot-replace semantics on a non-empty slot: remove first, then install.
        // (mayPlace already filtered to PartItem, but mayPlace doesn't see the existing
        // item — guard here.)
        if (occupied) be.removePart(face)
        be.installPart(face, partItem.partType.create())
        setChanged()
    }

    override fun setChanged() {
        be.setChanged()
    }

    override fun stillValid(player: Player): Boolean {
        val lvl = be.level ?: return false
        if (lvl.getBlockEntity(be.blockPos) !== be) return false
        return player.distanceToSqr(
            be.blockPos.x + 0.5,
            be.blockPos.y + 0.5,
            be.blockPos.z + 0.5,
        ) <= 64.0
    }

    override fun clearContent() {
        for (face in SLOT_FACES) {
            if (be.partOn(face) != null) be.removePart(face)
        }
        setChanged()
    }

    companion object {
        /**
         * Slot index → face mapping. Order is what determines the layout in
         * [com.brewingcoder.oc2.client.screen.AdapterPartsScreen]. Stable so
         * the menu's slot ids stay meaningful across versions.
         */
        val SLOT_FACES: List<Direction> = listOf(
            Direction.UP,
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
        )

        fun slotForFace(face: Direction): Int = SLOT_FACES.indexOf(face)
    }
}
