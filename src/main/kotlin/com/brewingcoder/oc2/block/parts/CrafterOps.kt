package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * MC-coupled craft executor for [CrafterPart]. Lives here (not in `platform/`)
 * because it imports `net.minecraft.world.item.crafting.*`. The CrafterPart
 * itself stays platform-pure for the slot bookkeeping; only [resolveOutput]
 * and [craft] dip into vanilla recipes.
 *
 * Both calls marshal to the server thread before touching the level — script
 * methods are invoked from the per-script worker thread, and recipe matching
 * walks the full RecipeManager which reads server registries.
 */
object CrafterOps {

    /**
     * Best-effort recipe-match for [pattern] against the level's RecipeManager.
     * Returns ("itemId", count) for a match, ("oc2:unmatched", 0) when the
     * pattern has cells but no recipe matches it, or (null, 0) when off-thread
     * resolution failed (no level / timeout).
     */
    fun resolveOutput(host: PartHost, pattern: RecipePattern): Pair<String?, Int> {
        if (pattern.isBlank) return null to 0
        val lvl = host.serverLevel() as? Level ?: return null to 0
        return onServerThread(lvl, "crafter resolveOutput") {
            val input = CraftingInput.of(RecipePattern.WIDTH, RecipePattern.HEIGHT, pattern.slots)
            val match = lvl.recipeManager.getRecipeFor(RecipeType.CRAFTING, input, lvl).orElse(null)
            if (match == null) {
                "oc2:unmatched" to 0
            } else {
                val out: ItemStack = match.value().assemble(input, lvl.registryAccess())
                BuiltInRegistries.ITEM.getKey(out.item).toString() to out.count
            }
        }
    }

    /**
     * Run the recipe in card [slot] up to [count] times. Pulls ingredients from
     * [source], pushes the output back into [source]. Returns the number of
     * crafts that completed (may be less than [count] if [source] runs dry or
     * fills up).
     *
     * Throws [IllegalArgumentException] when the slot is empty or unprogrammed,
     * or when the pattern doesn't match a registered recipe.
     */
    fun craft(
        host: PartHost,
        part: CrafterPart,
        slot: Int,
        count: Int,
        source: InventoryPeripheral,
    ): Int {
        if (count <= 0) return 0
        require(slot in 1..part.cards.containerSize) { "crafter slot $slot out of range 1..${part.cards.containerSize}" }
        val cardStack = part.cards.getItem(slot - 1)
        require(!cardStack.isEmpty && cardStack.item is RecipeCardItem) { "crafter slot $slot is empty" }
        val pattern = RecipeCardItem.pattern(cardStack)
            ?: throw IllegalArgumentException("crafter slot $slot card has no programmed pattern")
        require(!pattern.isBlank) { "crafter slot $slot card is blank" }

        val lvl = host.serverLevel() as? Level
            ?: throw IllegalStateException("crafter level unavailable (off-world?)")

        return onServerThread(lvl, "crafter craft") {
            val input = CraftingInput.of(RecipePattern.WIDTH, RecipePattern.HEIGHT, pattern.slots)
            val match = lvl.recipeManager.getRecipeFor(RecipeType.CRAFTING, input, lvl).orElseThrow {
                IllegalArgumentException("crafter slot $slot pattern matches no registered recipe")
            }
            val output = match.value().assemble(input, lvl.registryAccess())
            if (output.isEmpty) return@onServerThread 0

            // Pre-resolve remainder items (empty buckets, bowls, glass bottles, etc).
            // These come back parallel to the input slots — index i in the list is the
            // remainder for input slot i. Most cells are empty.
            val remainders = match.value().getRemainingItems(input)

            // Per-craft loop: each iteration consumes one set of ingredients from the
            // source inventory and reinserts a fresh output stack + any remainder
            // items (e.g. empty bucket from a milk-bucket recipe). Aborts cleanly on
            // first failed extract or insert.
            var done = 0
            val handler = (source as? InventoryPart.Wrapper)?.handler
                ?: return@onServerThread 0  // future: support arbitrary InventoryPeripheral
            repeat(count) {
                val plan = planExtract(handler, pattern.slots) ?: return@onServerThread done

                // Pre-flight: simulate output + remainder inserts so we don't dupe.
                // Build a transient mutable copy of slot stacks so back-to-back
                // simulations see each other's accumulated effect.
                val sim = SimHandler(handler)
                if (!sim.tryInsert(output.copy())) return@onServerThread done
                for (rem in remainders) {
                    if (rem.isEmpty) continue
                    if (!sim.tryInsert(rem.copy())) return@onServerThread done
                }

                // Commit: extract ingredients, then push output + remainders.
                for ((srcIdx, amount) in plan) {
                    handler.extractItem(srcIdx, amount, /* simulate = */ false)
                }
                insertAny(handler, output.copy())
                for (rem in remainders) {
                    if (rem.isEmpty) continue
                    insertAny(handler, rem.copy())
                }
                done++
            }
            done
        }
    }

    /**
     * Lightweight simulator over an [IItemHandler] that lets us test back-to-back
     * inserts (output + N remainder items) without committing — vanilla
     * `simulate=true` re-reads the live handler each call, which would let two
     * simulated inserts both succeed against the same empty slot. This keeps a
     * shadow copy of stack counts so each simulation accumulates.
     */
    private class SimHandler(private val handler: net.neoforged.neoforge.items.IItemHandler) {
        private val shadow: Array<ItemStack> =
            Array(handler.slots) { handler.getStackInSlot(it).copy() }

        /** Returns true if [stack] fits entirely (no leftover) given prior simulations. */
        fun tryInsert(stack: ItemStack): Boolean {
            if (stack.isEmpty) return true
            var remaining = stack
            for (i in shadow.indices) {
                if (remaining.isEmpty) return true
                val cur = shadow[i]
                val limit = minOf(handler.getSlotLimit(i), remaining.maxStackSize)
                if (cur.isEmpty) {
                    val take = minOf(limit, remaining.count)
                    val placed = remaining.copyWithCount(take)
                    if (!handler.isItemValid(i, placed)) continue
                    shadow[i] = placed
                    remaining = remaining.copyWithCount(remaining.count - take)
                } else if (ItemStack.isSameItemSameComponents(cur, remaining)) {
                    val space = limit - cur.count
                    if (space <= 0) continue
                    val take = minOf(space, remaining.count)
                    val merged = cur.copy()
                    merged.count = cur.count + take
                    if (!handler.isItemValid(i, merged)) continue
                    shadow[i] = merged
                    remaining = remaining.copyWithCount(remaining.count - take)
                }
            }
            return remaining.isEmpty
        }
    }

    /**
     * Plan how to satisfy each non-empty pattern slot from [handler]. Returns
     * a map of `srcSlot -> count` reflecting the simulated extractions, or null
     * if the inventory can't satisfy every required ingredient.
     *
     * Naive shaped-only matcher in v0 — we walk pattern cells and for each
     * non-empty cell scan the source inventory for ONE matching ItemStack to
     * extract. Doesn't yet honor Ingredient predicates (tag matches, etc.); will
     * land with a proper ingredient-aware matcher in a follow-up.
     */
    private fun planExtract(
        handler: net.neoforged.neoforge.items.IItemHandler,
        cells: List<ItemStack>,
    ): Map<Int, Int>? {
        val plan = HashMap<Int, Int>()
        // Track running count of each source slot we've reserved.
        val reserved = HashMap<Int, Int>()
        for (cell in cells) {
            if (cell.isEmpty) continue
            var found = false
            for (srcIdx in 0 until handler.slots) {
                val src = handler.getStackInSlot(srcIdx)
                if (src.isEmpty) continue
                if (!ItemStack.isSameItemSameComponents(src, cell)) continue
                val already = reserved.getOrDefault(srcIdx, 0)
                if (src.count - already < 1) continue
                reserved[srcIdx] = already + 1
                plan[srcIdx] = (plan[srcIdx] ?: 0) + 1
                found = true
                break
            }
            if (!found) return null
        }
        return plan
    }

    private fun insertAny(
        handler: net.neoforged.neoforge.items.IItemHandler,
        stack: ItemStack,
    ): ItemStack {
        var remaining = stack
        for (i in 0 until handler.slots) {
            if (remaining.isEmpty) break
            remaining = handler.insertItem(i, remaining, /* simulate = */ false)
        }
        return remaining
    }

    private inline fun <T> onServerThread(level: Level, opName: String, crossinline block: () -> T): T {
        val server = level.server ?: return block()
        if (server.isSameThread) return block()
        val supplier = Supplier { block() }
        return try {
            server.submit(supplier).get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            OpenComputers2.LOGGER.warn("crafter op '{}' failed: {}", opName, e.toString())
            throw e
        }
    }
}
