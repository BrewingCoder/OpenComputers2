package com.brewingcoder.oc2.block.bridge.adapters

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.bridge.ProtocolAdapter
import com.brewingcoder.oc2.block.parts.ItemHandlerHost
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.capabilities.Capabilities
import net.neoforged.neoforge.items.IItemHandler
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Last-resort adapter: claims any BlockEntity that exposes
 * [Capabilities.ItemHandler.BLOCK] when no specific adapter has already
 * matched. Two surface tiers depending on what the BE looks like:
 *
 *   - **Furnace-type** (AbstractFurnaceBlockEntity, or class/block id contains
 *     "furnace"/"smelter"): exposes the full standard-furnace surface — input
 *     + fuel + output slots, plus vanilla `RecipeType.SMELTING` recipe queries
 *     so scripts can `getOutputFor(inputId)` etc. Covers vanilla furnace,
 *     Iron Furnaces, JumboFurnace, mod variants. Mod-specific extras (heat,
 *     ash, multipliers) are intentionally not exposed — the recipes the
 *     machine consumes are the vanilla smelting pool.
 *   - **Generic inventory** (chest, barrel, hopper, mod storage blocks):
 *     inventory ops only. Recipe-query methods are omitted from `methods()`
 *     and return empty/null if force-called.
 *
 * Use `isFurnace()` to discover which tier you got. Use `getMachineType()`
 * for the runtime class.
 *
 * Order in [com.brewingcoder.oc2.block.bridge.BridgeDispatcher]: this MUST
 * be registered last so specific adapters (ZeroCore, Mekanism) win first.
 *
 * Soft-dep: depends only on NeoForge core capabilities, so no modid gate.
 */
object NeoForgeCapAdapter : ProtocolAdapter {
    override val id: String = "neoforge-cap"

    override fun canHandle(be: BlockEntity, face: Direction): Boolean =
        resolveHandler(be, face) != null

    override fun wrap(be: BlockEntity, face: Direction, name: String, data: String, location: Position): BridgePeripheral? {
        if (resolveHandler(be, face) == null) return null
        return CapPeripheral(name, be, face, location, data)
    }

    /**
     * Walk a side priority order looking for a non-null IItemHandler:
     *   1. `face.opposite` — the BE side facing the adapter (most natural)
     *   2. `null` — unsided handler (vanilla furnace exposes here)
     *   3. all six cardinal sides — some mods (e.g. JumboFurnace's exterior
     *      block) expose only on specific faces unrelated to which side the
     *      adapter is attached
     *
     * Uses the 5-arg cap form because some BEs (notably Mek factories — see
     * [MekanismMachineAdapter]) return null on the 3-arg form. Cap lookups
     * are internally cached by NeoForge — cheap to call per tick.
     */
    private fun resolveHandler(be: BlockEntity, face: Direction): IItemHandler? {
        val lvl = be.level as? ServerLevel ?: return null
        return onServerThread(lvl) {
            val state = be.blockState
            try {
                val sides = listOf<Direction?>(face.opposite, null) + Direction.entries
                for (side in sides) {
                    val h = lookupOnSide(lvl, be, state, side)
                    if (h != null) return@onServerThread h
                }
                null
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.debug("NeoForgeCapAdapter: cap lookup threw for {}", be.javaClass.simpleName, t)
                null
            }
        }
    }

    /**
     * Marshal [body] onto the server thread if we're not already there.
     * Required for cap lookups whose providers walk the world (e.g.
     * JumboFurnace's exterior BE forwarding to the core BE via
     * `level.getBlockEntity(corePos)` — that read is server-thread only).
     * Mirrors the pattern in [com.brewingcoder.oc2.block.MonitorBlockEntity].
     */
    private fun <T> onServerThread(lvl: ServerLevel, body: () -> T): T {
        val server = lvl.server
        if (server.isSameThread) return body()
        return server.submit(Supplier(body)).get(5, TimeUnit.SECONDS)
    }

    /**
     * Both cap-lookup forms exist because mods register caps via the BE-type
     * binding (5-arg works) OR via block lookup that goes through the world's
     * BE-resolution path (3-arg works). JumboFurnace's exterior BE registers
     * via BE-type but in practice the 3-arg form is what dispatches there.
     * Mek factories are the inverse — only 5-arg works. Try 5-arg first
     * (Mek case), fall back to 3-arg (Jumbo case).
     */
    private fun lookupOnSide(lvl: ServerLevel, be: BlockEntity, state: net.minecraft.world.level.block.state.BlockState, side: Direction?): IItemHandler? {
        try {
            lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, state, be, side)?.let { return it }
        } catch (_: Throwable) { }
        return try {
            lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, side)
        } catch (_: Throwable) { null }
    }

    private class CapPeripheral(
        override val name: String,
        private val be: BlockEntity,
        private val face: Direction,
        override val location: Position,
        override val data: String,
    ) : BridgePeripheral, InventoryPeripheral, ItemHandlerHost {
        override val protocol: String = "neoforge-cap"
        override val target: String = be.javaClass.name
        override val kind: String get() = "bridge"

        private val furnace: Boolean by lazy { detectFurnace() }

        override fun methods(): List<String> {
            val base = mutableListOf(
                "getMachineType",
                "getMachineFqn",
                "isFurnace",
                "_capProbe",
                "size",
                "list",
                "getItem",
                "find",
                "destroy",
                "push",
                "pull",
            )
            if (furnace) {
                base += listOf("getRecipes", "getOutputFor", "getInputsFor", "canConsume", "canProduce")
            }
            return base
        }

        override fun call(method: String, args: List<Any?>): Any? {
            return when (method) {
                "getMachineType" -> be.javaClass.simpleName
                "getMachineFqn" -> be.javaClass.name
                "isFurnace" -> furnace
                "_capProbe" -> capProbe()
                "size" -> size()
                "list" -> list()
                "getItem" -> {
                    val slot = (args.firstOrNull() as? Number)?.toInt() ?: return null
                    getItem(slot)?.let { mapOf("id" to it.id, "count" to it.count) }
                }
                "find" -> {
                    val id = args.firstOrNull() as? String ?: return -1
                    find(id)
                }
                "destroy" -> {
                    val slot = (args.getOrNull(0) as? Number)?.toInt() ?: return 0
                    val count = (args.getOrNull(1) as? Number)?.toInt() ?: Int.MAX_VALUE
                    destroy(slot, count)
                }
                "push" -> {
                    val slot = (args.getOrNull(0) as? Number)?.toInt() ?: return 0
                    val target = args.getOrNull(1) as? InventoryPeripheral ?: return 0
                    val count = (args.getOrNull(2) as? Number)?.toInt() ?: 64
                    val targetSlot = (args.getOrNull(3) as? Number)?.toInt()
                    push(slot, target, count, targetSlot)
                }
                "pull" -> {
                    val source = args.getOrNull(0) as? InventoryPeripheral ?: return 0
                    val slot = (args.getOrNull(1) as? Number)?.toInt() ?: return 0
                    val count = (args.getOrNull(2) as? Number)?.toInt() ?: 64
                    val targetSlot = (args.getOrNull(3) as? Number)?.toInt()
                    pull(source, slot, count, targetSlot)
                }
                "getRecipes" -> getRecipesList()
                "getOutputFor" -> {
                    val id = args.firstOrNull() as? String ?: return null
                    getOutputFor(id)
                }
                "getInputsFor" -> {
                    val id = args.firstOrNull() as? String ?: return emptyList<Any>()
                    getInputsFor(id)
                }
                "canConsume" -> {
                    val id = args.firstOrNull() as? String ?: return false
                    getOutputFor(id) != null
                }
                "canProduce" -> {
                    val id = args.firstOrNull() as? String ?: return false
                    getInputsFor(id).isNotEmpty()
                }
                else -> null
            }
        }

        // ---- ItemHandlerHost / InventoryPeripheral ----

        override val itemHandler: IItemHandler?
            get() = resolveHandler(be, face)

        override fun size(): Int = itemHandler?.slots ?: 0

        override fun getItem(slot: Int): ItemSnapshot? {
            val h = itemHandler ?: return null
            val idx = slot - 1
            if (idx !in 0 until h.slots) return null
            return snapshot(h.getStackInSlot(idx))
        }

        override fun list(): List<ItemSnapshot?> {
            val h = itemHandler ?: return emptyList()
            return (0 until h.slots).map { snapshot(h.getStackInSlot(it)) }
        }

        override fun find(itemId: String): Int {
            val h = itemHandler ?: return -1
            for (i in 0 until h.slots) {
                val s = h.getStackInSlot(i)
                if (s.isEmpty) continue
                if (idFor(s) == itemId) return i + 1
            }
            return -1
        }

        override fun destroy(slot: Int, count: Int): Int {
            val h = itemHandler ?: return 0
            val idx = slot - 1
            if (idx !in 0 until h.slots) return 0
            return h.extractItem(idx, count.coerceAtLeast(0), false).count
        }

        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int {
            val srcH = itemHandler ?: return 0
            val src = slot - 1
            if (src !in 0 until srcH.slots) return 0
            val toMove = srcH.extractItem(src, count.coerceAtLeast(0), true)
            if (toMove.isEmpty) return 0
            val targetH = (target as? ItemHandlerHost)?.itemHandler ?: return 0
            val remainder = if (targetSlot != null) tryInsertAt(targetH, targetSlot - 1, toMove)
                            else tryInsertAny(targetH, toMove)
            val moved = toMove.count - remainder.count
            if (moved > 0) srcH.extractItem(src, moved, false)
            return moved
        }

        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int {
            val dstH = itemHandler ?: return 0
            val srcH = (source as? ItemHandlerHost)?.itemHandler ?: return 0
            val src = slot - 1
            if (src !in 0 until srcH.slots) return 0
            val toMove = srcH.extractItem(src, count.coerceAtLeast(0), true)
            if (toMove.isEmpty) return 0
            val remainder = if (targetSlot != null) tryInsertAt(dstH, targetSlot - 1, toMove)
                            else tryInsertAny(dstH, toMove)
            val moved = toMove.count - remainder.count
            if (moved > 0) srcH.extractItem(src, moved, false)
            return moved
        }

        private fun tryInsertAt(h: IItemHandler, idx: Int, stack: ItemStack): ItemStack {
            if (idx !in 0 until h.slots) return stack
            return h.insertItem(idx, stack, false)
        }

        private fun tryInsertAny(h: IItemHandler, stack: ItemStack): ItemStack {
            var remainder = stack
            for (i in 0 until h.slots) {
                if (remainder.isEmpty) break
                remainder = h.insertItem(i, remainder, false)
            }
            return remainder
        }

        private fun snapshot(stack: ItemStack): ItemSnapshot? {
            if (stack.isEmpty) return null
            return ItemSnapshot(idFor(stack), stack.count)
        }

        private fun idFor(stack: ItemStack): String =
            BuiltInRegistries.ITEM.getKey(stack.item).toString()

        // ---- Furnace-type detection ----

        /**
         * True if this BE looks like a vanilla-smelting consumer. Three signals,
         * any one wins:
         *   1. instance of [AbstractFurnaceBlockEntity] (vanilla furnace + most
         *      forks like Iron Furnaces extend this)
         *   2. BE class FQN contains "furnace" or "smelter" (catches JumboFurnace
         *      whose `FurnaceCoreBlockEntity` may not extend the abstract base)
         *   3. block registry id contains "furnace" or "smelter" (e.g.
         *      `mekanism:smelter` if it ever falls through)
         * Lossy by design — false positives are harmless (recipe queries return
         * empty), false negatives just mean recipe methods don't show up.
         */
        private fun detectFurnace(): Boolean {
            if (be is AbstractFurnaceBlockEntity) return true
            val fqn = be.javaClass.name.lowercase()
            if (fqn.contains("furnace") || fqn.contains("smelter")) return true
            val blockId = try {
                BuiltInRegistries.BLOCK.getKey(be.blockState.block).toString().lowercase()
            } catch (_: Throwable) { "" }
            return blockId.contains("furnace") || blockId.contains("smelter")
        }

        // ---- Recipe query (vanilla SMELTING — gated on furnace detection) ----

        private fun smeltingRecipes(): List<RecipeHolder<*>> {
            if (!furnace) return emptyList()
            val server = (be.level as? ServerLevel)?.server ?: return emptyList()
            return try {
                @Suppress("UNCHECKED_CAST")
                server.recipeManager.getAllRecipesFor(RecipeType.SMELTING)
                    as List<RecipeHolder<*>>
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.debug("NeoForgeCapAdapter: recipe lookup failed", t)
                emptyList()
            }
        }

        private fun resultStackOf(holder: RecipeHolder<*>): ItemStack {
            val server = (be.level as? ServerLevel)?.server ?: return ItemStack.EMPTY
            @Suppress("UNCHECKED_CAST")
            val recipe = holder.value as net.minecraft.world.item.crafting.Recipe<Any>
            return try { recipe.getResultItem(server.registryAccess()) } catch (_: Throwable) { ItemStack.EMPTY }
        }

        private fun ingredientItemIdsOf(holder: RecipeHolder<*>): List<String> {
            val recipe = holder.value
            val ing = try { recipe.ingredients.firstOrNull() } catch (_: Throwable) { null } ?: return emptyList()
            return try {
                ing.items.map { idFor(it) }.distinct()
            } catch (_: Throwable) { emptyList() }
        }

        private fun getRecipesList(): List<Map<String, Any?>> {
            val out = mutableListOf<Map<String, Any?>>()
            for (h in smeltingRecipes()) {
                val output = resultStackOf(h)
                if (output.isEmpty) continue
                val inputs = ingredientItemIdsOf(h)
                if (inputs.isEmpty()) continue
                out.add(mapOf(
                    "inputs" to inputs,
                    "output" to mapOf("id" to idFor(output), "count" to output.count),
                ))
            }
            return out
        }

        private fun getOutputFor(inputId: String): Map<String, Any?>? {
            for (h in smeltingRecipes()) {
                if (inputId !in ingredientItemIdsOf(h)) continue
                val output = resultStackOf(h)
                if (output.isEmpty) continue
                return mapOf("id" to idFor(output), "count" to output.count)
            }
            return null
        }

        private fun getInputsFor(outputId: String): List<Map<String, Any?>> {
            val out = mutableListOf<Map<String, Any?>>()
            for (h in smeltingRecipes()) {
                val output = resultStackOf(h)
                if (output.isEmpty || idFor(output) != outputId) continue
                val inputs = ingredientItemIdsOf(h)
                if (inputs.isEmpty()) continue
                out.add(mapOf("inputs" to inputs, "count" to output.count))
            }
            return out
        }

        private fun capProbe(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            out["bridgeFace"] = face.serializedName
            out["bePos"] = "${be.blockPos.x},${be.blockPos.y},${be.blockPos.z}"
            out["beClass"] = be.javaClass.name
            val lvl = be.level as? ServerLevel ?: return out
            // Run on server thread — JumboFurnace's cap provider walks the world
            // via getCoreTile() and would crash off-thread.
            return onServerThread(lvl) {
                val state = be.blockState
                val sides = listOf<Direction?>(null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)
                for (side in sides) {
                    val key = side?.serializedName ?: "null"
                    out[key] = try {
                        val h5 = lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, state, be, side)
                        val h3 = if (h5 == null) lvl.getCapability(Capabilities.ItemHandler.BLOCK, be.blockPos, side) else null
                        when {
                            h5 != null -> "5arg slots=${h5.slots}"
                            h3 != null -> "3arg slots=${h3.slots}"
                            else -> "null"
                        }
                    } catch (t: Throwable) { "threw:${t.javaClass.simpleName}:${t.message}" }
                }
                out
            }
        }
    }
}
