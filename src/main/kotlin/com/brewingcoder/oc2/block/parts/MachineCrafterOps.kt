package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * MC-coupled craft executor for [MachineCrafterPart]. Lives here (not in
 * `platform/`) because it touches NeoForge capability handles. The Part itself
 * stays platform-pure for the slot bookkeeping; only [craft] dips into the
 * adjacent machine's capabilities.
 *
 * Routing model — for each "craft cycle":
 *
 *   1. Pull every non-empty input slot from `source` (machine cards have a flat
 *      ingredient list, not a positional grid — slot order is irrelevant).
 *   2. Optionally drain `fluidSource` for the recipe's fluid input and push
 *      into the machine's [IFluidHandler] (when the machine exposes one).
 *   3. Push items into the machine's [IItemHandler] via insert-any.
 *   4. Drain the machine's output (whatever items appear that *aren't* one of
 *      the inputs) back into `source`.
 *
 * **Blocking mode** (per-recipe): when `pattern.blocking == true`, before
 * starting a new cycle we check that every machine input slot containing one
 * of our recipe inputs is empty. This is a simple correctness fence — the prior
 * craft must have been consumed before we inject more. Non-blocking cards skip
 * the check and fire-and-forget.
 *
 * Output extraction is best-effort: machines have no synchronous "is recipe
 * done?" signal, so we drain whatever is present after the inject. Scripts
 * that need strong "wait until output appears" semantics should call `craft`
 * in a loop or sleep between calls.
 */
object MachineCrafterOps {

    /** Convenience accessor — surface a stable item id for a stamped output stack. */
    fun itemId(stack: ItemStack): String =
        BuiltInRegistries.ITEM.getKey(stack.item).toString()

    /**
     * Run the machine recipe in card [slot] up to [count] times. Returns the
     * number of cycles that successfully injected ingredients; output drain
     * is best-effort and not counted (since async machines may not have produced
     * anything yet by the time we return).
     */
    fun craft(
        host: PartHost,
        part: MachineCrafterPart,
        slot: Int,
        count: Int,
        source: InventoryPeripheral,
        fluidSource: FluidPeripheral?,
    ): Int {
        if (count <= 0) return 0
        require(slot in 1..part.cards.containerSize) { "machine_crafter slot $slot out of range 1..${part.cards.containerSize}" }
        val cardStack = part.cards.getItem(slot - 1)
        require(!cardStack.isEmpty && cardStack.item is RecipeCardItem) { "machine_crafter slot $slot is empty" }
        val pattern = RecipeCardItem.pattern(cardStack)
            ?: throw IllegalArgumentException("machine_crafter slot $slot card has no programmed pattern")
        require(!pattern.isBlank) { "machine_crafter slot $slot card is blank" }
        require(pattern.mode == RecipePattern.Mode.MACHINE) {
            "machine_crafter slot $slot card is in ${pattern.mode.id} mode (expected machine) — use the regular crafter"
        }

        val lvl = host.serverLevel() as? Level
            ?: throw IllegalStateException("machine_crafter level unavailable (off-world?)")

        return onServerThread(lvl, "machine_crafter craft") {
            val machineItems = host.lookupCapability(PartCapabilityKeys.ITEM)
                ?: throw IllegalStateException("machine_crafter target exposes no IItemHandler")
            val machineFluids = host.lookupCapability(PartCapabilityKeys.FLUID)

            val srcHandler = (source as? InventoryPart.Wrapper)?.handler
                ?: return@onServerThread 0  // future: support arbitrary InventoryPeripheral wrappers
            val srcFluid = if (fluidSource != null) {
                fluidSourceHandler(fluidSource) ?: return@onServerThread 0
            } else null

            // Deduplicate the ingredient list — the card has 9 cells but they
            // act as a flat list, so merge identical stacks first.
            val ingredients = mergeIngredients(pattern.slots)
            if (ingredients.isEmpty()) return@onServerThread 0

            var done = 0
            repeat(count) {
                if (pattern.blocking && !machineInputDrained(machineItems, ingredients)) {
                    return@onServerThread done
                }

                val plan = planExtract(srcHandler, ingredients) ?: return@onServerThread done

                if (!pattern.fluidIn.isEmpty) {
                    if (srcFluid == null) return@onServerThread done
                    if (machineFluids == null) return@onServerThread done
                    if (!simulateFluidPush(srcFluid, machineFluids, pattern.fluidIn)) return@onServerThread done
                }

                if (!simulateMachineInsert(machineItems, ingredients)) return@onServerThread done

                // Commit phase: extract source items, drain source fluid, push
                // both into the machine. Order is item-first so a fluid failure
                // doesn't strand items mid-route.
                for ((srcIdx, amount) in plan) {
                    srcHandler.extractItem(srcIdx, amount, /* simulate = */ false)
                }
                for (ing in ingredients) {
                    insertAny(machineItems, ing.copy())
                }
                if (!pattern.fluidIn.isEmpty && srcFluid != null && machineFluids != null) {
                    pushFluid(srcFluid, machineFluids, pattern.fluidIn)
                }
                done++
            }

            // Best-effort output drain: pull anything from the machine that
            // isn't one of our inputs back into source.
            drainMachineOutput(machineItems, srcHandler, ingredients)

            done
        }
    }

    private fun fluidSourceHandler(fluidSource: FluidPeripheral): IFluidHandler? =
        (fluidSource as? FluidPart.Wrapper)?.handler

    /** Merge identical-by-id+components stacks — cards treat their 9 cells as a flat list. */
    private fun mergeIngredients(cells: List<ItemStack>): List<ItemStack> {
        val merged = ArrayList<ItemStack>()
        for (cell in cells) {
            if (cell.isEmpty) continue
            val match = merged.firstOrNull { ItemStack.isSameItemSameComponents(it, cell) }
            if (match != null) {
                match.count += cell.count
            } else {
                merged.add(cell.copy())
            }
        }
        return merged
    }

    /**
     * Plan how to satisfy each ingredient from [src]. Returns map of `srcSlot ->
     * count`, or null if the inventory can't satisfy every ingredient.
     */
    private fun planExtract(src: IItemHandler, ingredients: List<ItemStack>): Map<Int, Int>? {
        val plan = HashMap<Int, Int>()
        val reserved = HashMap<Int, Int>()
        for (ing in ingredients) {
            var remaining = ing.count
            for (srcIdx in 0 until src.slots) {
                if (remaining <= 0) break
                val stack = src.getStackInSlot(srcIdx)
                if (stack.isEmpty) continue
                if (!ItemStack.isSameItemSameComponents(stack, ing)) continue
                val already = reserved.getOrDefault(srcIdx, 0)
                val available = stack.count - already
                if (available <= 0) continue
                val take = minOf(available, remaining)
                reserved[srcIdx] = already + take
                plan[srcIdx] = (plan[srcIdx] ?: 0) + take
                remaining -= take
            }
            if (remaining > 0) return null
        }
        return plan
    }

    /** Returns true if all ingredient amounts can be inserted into the machine. */
    private fun simulateMachineInsert(machine: IItemHandler, ingredients: List<ItemStack>): Boolean {
        // Pure simulation — vanilla simulate=true reads the same slots each call,
        // which is fine for one-stack-at-a-time across distinct ingredient ids.
        // For ingredients sharing an id (already merged) we ran through merge.
        for (ing in ingredients) {
            var remaining = ing.copy()
            for (i in 0 until machine.slots) {
                if (remaining.isEmpty) break
                remaining = machine.insertItem(i, remaining, /* simulate = */ true)
            }
            if (!remaining.isEmpty) return false
        }
        return true
    }

    /** Returns true if every input-slot of the machine is empty of our recipe inputs. */
    private fun machineInputDrained(machine: IItemHandler, ingredients: List<ItemStack>): Boolean {
        for (i in 0 until machine.slots) {
            val stack = machine.getStackInSlot(i)
            if (stack.isEmpty) continue
            for (ing in ingredients) {
                if (ItemStack.isSameItemSameComponents(stack, ing)) return false
            }
        }
        return true
    }

    /** Drain any non-input items from the machine into [dest]. */
    private fun drainMachineOutput(machine: IItemHandler, dest: IItemHandler, inputs: List<ItemStack>) {
        for (i in 0 until machine.slots) {
            val stack = machine.getStackInSlot(i)
            if (stack.isEmpty) continue
            // Skip leftover input ingredient stacks — those are still being
            // consumed by the machine; only drain what looks like output.
            if (inputs.any { ItemStack.isSameItemSameComponents(it, stack) }) continue
            val sim = machine.extractItem(i, stack.count, /* simulate = */ true)
            if (sim.isEmpty) continue
            val destSim = simulateInsertAny(dest, sim)
            val taking = sim.count - destSim.count
            if (taking <= 0) continue
            val real = machine.extractItem(i, taking, /* simulate = */ false)
            insertAny(dest, real)
        }
    }

    private fun simulateFluidPush(srcF: IFluidHandler, dstF: IFluidHandler, want: RecipePattern.FluidSpec): Boolean {
        val target = ResourceLocation.tryParse(want.id) ?: return false
        // Walk source tanks for a fluid matching id; we don't support tag matching yet.
        val drained = drainExact(srcF, target, want.mB, IFluidHandler.FluidAction.SIMULATE)
        if (drained.amount < want.mB) return false
        val filled = dstF.fill(drained, IFluidHandler.FluidAction.SIMULATE)
        return filled >= want.mB
    }

    private fun pushFluid(srcF: IFluidHandler, dstF: IFluidHandler, want: RecipePattern.FluidSpec) {
        val target = ResourceLocation.tryParse(want.id) ?: return
        val drained = drainExact(srcF, target, want.mB, IFluidHandler.FluidAction.EXECUTE)
        if (drained.isEmpty) return
        dstF.fill(drained, IFluidHandler.FluidAction.EXECUTE)
    }

    private fun drainExact(srcF: IFluidHandler, fluidId: ResourceLocation, mB: Int, action: IFluidHandler.FluidAction): FluidStack {
        for (tank in 0 until srcF.tanks) {
            val held = srcF.getFluidInTank(tank)
            if (held.isEmpty) continue
            val heldId = BuiltInRegistries.FLUID.getKey(held.fluid)
            if (heldId != fluidId) continue
            val drain = srcF.drain(held.copyWithAmount(mB), action)
            if (!drain.isEmpty) return drain
        }
        return FluidStack.EMPTY
    }

    private fun insertAny(handler: IItemHandler, stack: ItemStack): ItemStack {
        var remaining = stack
        for (i in 0 until handler.slots) {
            if (remaining.isEmpty) break
            remaining = handler.insertItem(i, remaining, /* simulate = */ false)
        }
        return remaining
    }

    private fun simulateInsertAny(handler: IItemHandler, stack: ItemStack): ItemStack {
        var remaining = stack
        for (i in 0 until handler.slots) {
            if (remaining.isEmpty) break
            remaining = handler.insertItem(i, remaining, /* simulate = */ true)
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
            OpenComputers2.LOGGER.warn("machine_crafter op '{}' failed: {}", opName, e.toString())
            throw e
        }
    }
}
