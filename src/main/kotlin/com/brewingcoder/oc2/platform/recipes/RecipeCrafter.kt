package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.peripheral.CrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.MachineCrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.RecipeIngredient
import com.brewingcoder.oc2.platform.script.ScriptEnv

/**
 * Pure-platform orchestrator backing the script-side `recipes.craft(itemId, count)`
 * global. The script supplies *what* it wants — output id and amount — and this
 * class figures out *where* the recipe lives and *how* to feed it.
 *
 * Resolution flow for `craft("graphite_ingot", 9)`:
 *   1. Walk every `machine_crafter` and `crafter` peripheral on the channel.
 *   2. Find a programmed card whose stamped output matches `itemId`. Match
 *      precedence: exact id (`bigreactors:graphite_ingot`) → unique tail match
 *      (`graphite_ingot` → `bigreactors:graphite_ingot` if it's the only one).
 *   3. Compute `cycles = ceil(count / outputCount)` — recipes producing N items
 *      per cycle round up so the result is "at least the requested amount".
 *   4. Pick a source inventory that already holds enough of every ingredient
 *      for `cycles` cycles. If no single inventory has everything, error with
 *      a deficit list — scripts can pre-stage with `inventory.get` and retry.
 *   5. Pick a fluid source the same way when the recipe carries `fluidIn`.
 *   6. Delegate to the existing per-peripheral `craft(slot, cycles, source)`.
 *
 * Returns the *expected* item count produced (cycles_done × outputCount).
 * Output drain is best-effort — same as direct peripheral calls — so async
 * machines may need a follow-up call to flush late-finishing outputs.
 */
class RecipeCrafter(private val env: ScriptEnv) {

    /**
     * Craft until at least [count] of [itemId] would be produced. Returns the
     * expected number of items (cycles_done × per-cycle output count); zero
     * if the cycles couldn't begin (e.g. blocking-mode card with prior craft
     * still in the machine).
     */
    fun craft(itemId: String, count: Int = 1): Int {
        require(count > 0) { "count must be > 0 (got $count)" }
        val match = resolve(itemId)
        val cycles = ceilDiv(count, match.outputCount)

        val source = pickItemSource(match, cycles) ?: throw IllegalStateException(
            "no inventory on this channel holds ingredients for '${match.outputId}': " +
                "needs ${match.inputs.joinToString { "${it.count * cycles}× ${it.id}" }} " +
                "across $cycles cycle(s)"
        )
        val fluidSource = match.fluidIn?.let { (id, mb) ->
            pickFluidSource(id, mb * cycles) ?: throw IllegalStateException(
                "no fluid peripheral on this channel holds $id ${mb * cycles} mB " +
                    "for $cycles cycle(s)"
            )
        }

        val done = match.invoke(cycles, source, fluidSource)
        return done * match.outputCount
    }

    // ---- match resolution ----

    private fun resolve(itemId: String): Match {
        val all = enumerate()
        if (all.isEmpty()) throw IllegalStateException(
            "no programmed crafter cards on this channel — install a machine_crafter " +
                "or crafter part with a programmed Recipe Card"
        )
        val exact = all.filter { it.outputId == itemId }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) throw IllegalStateException(ambiguityMsg(itemId, exact))

        val tailNeedle = ":$itemId"
        val tail = all.filter { it.outputId.endsWith(tailNeedle) }
        if (tail.size == 1) return tail.single()
        if (tail.size > 1) throw IllegalStateException(ambiguityMsg(itemId, tail))

        throw IllegalStateException(
            "no recipe known for '$itemId' on this channel — installed cards stamp: " +
                all.joinToString { it.outputId }
        )
    }

    private fun enumerate(): List<Match> {
        val out = ArrayList<Match>()
        for (p in env.listPeripherals("machine_crafter").filterIsInstance<MachineCrafterPeripheral>()) {
            for (snap in p.list()) {
                val output = snap?.output ?: continue
                if (snap.outputCount <= 0 || snap.inputs.isEmpty()) continue
                val fluid = if (snap.fluidIn != null && snap.fluidInMb > 0)
                    snap.fluidIn to snap.fluidInMb else null
                out.add(MachineMatch(p, snap.slot, output, snap.outputCount, snap.inputs, fluid))
            }
        }
        for (p in env.listPeripherals("crafter").filterIsInstance<CrafterPeripheral>()) {
            for (snap in p.list()) {
                val output = snap?.output ?: continue
                if (output == "oc2:unmatched" || snap.outputCount <= 0 || snap.inputs.isEmpty()) continue
                out.add(TableMatch(p, snap.slot, output, snap.outputCount, snap.inputs))
            }
        }
        return out
    }

    private fun ambiguityMsg(itemId: String, matches: List<Match>): String =
        "ambiguous recipe '$itemId' — matches: " +
            matches.joinToString { "${it.peripheralName}#${it.slot} (${it.outputId})" } +
            "; use the explicit `peripheral.find(...).craft(slot,...)` API to disambiguate"

    // ---- sourcing ----

    private fun pickItemSource(match: Match, cycles: Int): InventoryPeripheral? {
        val invs = env.listPeripherals("inventory").filterIsInstance<InventoryPeripheral>()
        return invs.firstOrNull { inv ->
            match.inputs.all { ing -> totalCount(inv, ing.id) >= ing.count * cycles }
        }
    }

    private fun pickFluidSource(fluidId: String, mB: Int): FluidPeripheral? {
        val fluids = env.listPeripherals("fluid").filterIsInstance<FluidPeripheral>()
        return fluids.firstOrNull { f ->
            f.list().sumOf { snap -> if (snap?.id == fluidId) snap.amount else 0 } >= mB
        }
    }

    private fun totalCount(inv: InventoryPeripheral, itemId: String): Int =
        inv.list().sumOf { snap -> if (snap?.id == itemId) snap.count else 0 }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    // ---- internal sum types ----

    private sealed class Match {
        abstract val peripheralName: String
        abstract val slot: Int
        abstract val outputId: String
        abstract val outputCount: Int
        abstract val inputs: List<RecipeIngredient>
        abstract val fluidIn: Pair<String, Int>?
        abstract fun invoke(cycles: Int, src: InventoryPeripheral, fluidSrc: FluidPeripheral?): Int
    }

    private class MachineMatch(
        val mc: MachineCrafterPeripheral,
        override val slot: Int,
        override val outputId: String,
        override val outputCount: Int,
        override val inputs: List<RecipeIngredient>,
        override val fluidIn: Pair<String, Int>?,
    ) : Match() {
        override val peripheralName: String get() = mc.name
        override fun invoke(cycles: Int, src: InventoryPeripheral, fluidSrc: FluidPeripheral?): Int =
            mc.craft(slot, cycles, src, fluidSrc)
    }

    private class TableMatch(
        val cr: CrafterPeripheral,
        override val slot: Int,
        override val outputId: String,
        override val outputCount: Int,
        override val inputs: List<RecipeIngredient>,
    ) : Match() {
        override val fluidIn: Pair<String, Int>? get() = null
        override val peripheralName: String get() = cr.name
        override fun invoke(cycles: Int, src: InventoryPeripheral, fluidSrc: FluidPeripheral?): Int =
            cr.craft(slot, cycles, src)
    }
}
