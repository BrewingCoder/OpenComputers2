package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.peripheral.BlockPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * MC-coupled helpers for [BlockPart]. Lives here (not in `platform/`) because
 * it imports `net.minecraft.*`. The Part interface itself stays platform-pure.
 *
 * Both [read] and [harvest] marshal to the server thread before touching the
 * level — script methods are invoked from the per-script worker thread, and
 * level reads/writes are not safe off-thread (we hit this exact bug with
 * MonitorBlockEntity earlier in the project).
 */
object BlockPartOps {

    /** Read a snapshot of the block at [blockPos] + [face]. Null if level is gone. */
    fun read(level: Level?, blockPos: BlockPos, face: Direction): BlockPeripheral.BlockReadout? {
        val lvl = level ?: return null
        return onServerThread(lvl, "block read") {
            val target = blockPos.relative(face)
            val state = lvl.getBlockState(target)
            val id = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            val be = lvl.getBlockEntity(target)
            val nbt = if (be != null) {
                val tag = be.saveWithFullMetadata(lvl.registryAccess())
                NbtUtils.toPrettyComponent(tag).string
            } else null
            BlockPeripheral.BlockReadout(
                id = id,
                isAir = state.isAir,
                pos = Triple(target.x, target.y, target.z),
                lightLevel = lvl.getMaxLocalRawBrightness(target),
                redstonePower = lvl.getBestNeighborSignal(target),
                hardness = state.getDestroySpeed(lvl, target),
                nbt = nbt,
            )
        }
    }

    /**
     * Break the adjacent block, route loot into [target] inventory; excess
     * spawns as ItemEntities at the broken position. Returns snapshots of
     * items routed to [target] (NOT items dropped on ground).
     */
    fun harvest(
        level: Level?,
        blockPos: BlockPos,
        face: Direction,
        target: InventoryPeripheral?,
    ): List<InventoryPeripheral.ItemSnapshot> {
        val lvl = level as? ServerLevel ?: return emptyList()
        return onServerThread(lvl, "block harvest") {
            val pos = blockPos.relative(face)
            val state = lvl.getBlockState(pos)
            if (state.isAir) return@onServerThread emptyList()
            // -1 hardness = unbreakable (bedrock, barriers, etc.)
            if (state.getDestroySpeed(lvl, pos) < 0) return@onServerThread emptyList()
            val be = lvl.getBlockEntity(pos)
            // Loot table results — same as a player breaking with no specific tool.
            val drops: List<ItemStack> = Block.getDrops(state, lvl, pos, be)
            // Atomically: clear the block first so we never have block + items both existing.
            lvl.setBlock(pos, lvl.getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL)
            val moved = mutableListOf<InventoryPeripheral.ItemSnapshot>()
            for (stack in drops) {
                if (stack.isEmpty) continue
                // Capture id BEFORE routing — routeIntoInventory shrinks the
                // stack, and a fully-shrunk stack reports id="minecraft:air".
                val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                val originalCount = stack.count
                val routedCount = if (target != null) routeIntoInventory(target, stack) else 0
                if (routedCount > 0) {
                    moved.add(InventoryPeripheral.ItemSnapshot(itemId, routedCount))
                }
                // Whatever didn't route goes to the ground at the broken-block pos.
                val remainder = originalCount - routedCount
                if (remainder > 0) {
                    val leftover = stack.copyWithCount(remainder)
                    val ent = ItemEntity(
                        lvl,
                        pos.x + 0.5, pos.y + 0.25, pos.z + 0.5,
                        leftover,
                        0.0, 0.1, 0.0,
                    )
                    ent.setDefaultPickUpDelay()
                    lvl.addFreshEntity(ent)
                }
            }
            OpenComputers2.LOGGER.info("block harvest @ {}: {} drop(s), {} routed to inventory",
                pos, drops.size, moved.size)
            moved
        }
    }

    /**
     * Insert [stack] into [target] slot-by-slot until full or exhausted.
     * Returns the count actually inserted; [stack] is shrunk to match.
     *
     * We bypass [InventoryPeripheral.pull] (which assumes Wrapper-to-Wrapper)
     * and reach the real [net.neoforged.neoforge.items.IItemHandler] via
     * [InventoryPart.Wrapper.handler]. Targets that aren't InventoryPart
     * (e.g. test fakes, future inventory impls) silently route 0 — caller
     * sees the items go to ground instead.
     */
    private fun routeIntoInventory(target: InventoryPeripheral, stack: ItemStack): Int {
        val handler = (target as? InventoryPart.Wrapper)?.handler ?: return 0
        var remaining = stack.copy()
        for (i in 0 until handler.slots) {
            if (remaining.isEmpty) break
            remaining = handler.insertItem(i, remaining, /* simulate = */ false)
        }
        val moved = stack.count - remaining.count
        if (moved > 0) stack.shrink(moved)
        return moved
    }

    /**
     * Synchronous server-thread marshal. If already on server thread, runs
     * inline. Otherwise submits to the server executor and blocks up to 5s.
     * Logs + returns null/empty equivalent on timeout (caller checks).
     */
    private inline fun <T> onServerThread(level: Level, opName: String, crossinline block: () -> T): T {
        val server = level.server ?: return block()  // client level — best-effort
        if (server.isSameThread) return block()
        val supplier = Supplier { block() }
        return server.submit(supplier).get(5, TimeUnit.SECONDS)
    }

}
