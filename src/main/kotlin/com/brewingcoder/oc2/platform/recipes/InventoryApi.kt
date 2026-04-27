package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.script.ScriptEnv

/**
 * Pure-platform (Rule D) entry point for the script-side `inventory` global.
 *
 *   `inventory.list()`                          — every InventoryPeripheral on the channel
 *   `inventory.find()` / `find("name")`         — first match (or by name)
 *   `inventory.get(id, n, target [, from])`     — pull `n` of `id` into `target` from scattered chests
 *   `inventory.drain(machine [, id [, to]])`    — empty `machine`'s slots back to chests
 *   `inventory.put(id, n, source, target)`      — explicit point-to-point move
 *
 * Default rankings (overridable by passing `from`/`to` lists):
 *   - `get`:   sources sorted by ASCENDING count of [itemId] — drain the smallest scattered piles first.
 *   - `drain`: sinks sorted by DESCENDING count of the item being moved — consolidate into the largest stash.
 *
 * Both verbs are *snapshot* operations: they move what is currently visible
 * and return the count moved. Higher-order verbs that loop until idle (`smelt`,
 * `produce`) are layered on top of these primitives.
 */
class InventoryApi(private val env: ScriptEnv) {

    /** Every InventoryPeripheral on the host channel. */
    fun list(): List<InventoryPeripheral> =
        env.listPeripherals("inventory").filterIsInstance<InventoryPeripheral>()

    /** First inventory matching [name], or the first inventory if [name] is null. */
    fun find(name: String? = null): InventoryPeripheral? {
        val all = list()
        return if (name == null) all.firstOrNull() else all.firstOrNull { it.name == name }
    }

    /**
     * Move up to [count] of [itemId] into [target], pulling from inventories that hold it.
     *
     * Source ranking (default): ASCENDING by count of [itemId] — drains the smallest piles
     * first so scattered chests get cleaned out before the main stash is touched. Pass
     * an explicit [from] list to override; the list order is then honored as-is.
     *
     * [target] is excluded from the auto-discovered source set so a chest can't pull from
     * itself. Returns the total moved.
     */
    fun get(
        itemId: String,
        count: Int,
        target: InventoryPeripheral,
        from: List<InventoryPeripheral>? = null,
    ): Int {
        if (count <= 0) return 0
        val sources = if (from != null) {
            from.filter { it !== target }
        } else {
            list().asSequence()
                .filter { it !== target && it.find(itemId) > 0 }
                .sortedBy { totalCount(it, itemId) }
                .toList()
        }
        var remaining = count
        var moved = 0
        for (src in sources) {
            if (remaining <= 0) break
            val pulled = drainItemTo(src, itemId, target, remaining)
            moved += pulled
            remaining -= pulled
        }
        return moved
    }

    /**
     * Empty [machine]'s slots into nearby inventories. Snapshot operation — no
     * loop, no idle-break; what's there NOW is what gets moved.
     *
     * If [itemId] is null every non-empty slot is candidate; otherwise only slots
     * matching that id. Sink ranking (default): DESCENDING by count of the item
     * being moved — pile onto the biggest existing stack first (compacting drawer /
     * AE2-style consolidation). Pass [to] to override; list order honored as-is.
     *
     * Returns total moved.
     */
    fun drain(
        machine: InventoryPeripheral,
        itemId: String? = null,
        to: List<InventoryPeripheral>? = null,
    ): Int {
        val candidates = machine.list()
            .withIndex()
            .filter { (_, snap) -> snap != null && (itemId == null || snap.id == itemId) }
        if (candidates.isEmpty()) return 0

        var moved = 0
        for ((idx, snap) in candidates) {
            val id = snap!!.id
            val sinks = if (to != null) {
                to.filter { it !== machine }
            } else {
                list().asSequence()
                    .filter { it !== machine }
                    .sortedByDescending { totalCount(it, id) }
                    .toList()
            }
            for (sink in sinks) {
                if (machine.getItem(idx + 1) == null) break
                val pushed = machine.push(idx + 1, sink)
                moved += pushed
            }
        }
        return moved
    }

    /**
     * Explicit move: push up to [count] of [itemId] from [source] to [target].
     * No source/sink discovery; caller picked both endpoints. Returns total moved.
     */
    fun put(
        itemId: String,
        count: Int,
        source: InventoryPeripheral,
        target: InventoryPeripheral,
    ): Int = drainItemTo(source, itemId, target, count)

    // ---- helpers ----

    private fun totalCount(inv: InventoryPeripheral, itemId: String): Int =
        inv.list().sumOf { snap -> if (snap?.id == itemId) snap.count else 0 }

    private fun drainItemTo(
        src: InventoryPeripheral,
        itemId: String,
        target: InventoryPeripheral,
        max: Int,
    ): Int {
        if (max <= 0) return 0
        var remaining = max
        var moved = 0
        while (remaining > 0) {
            val slot = src.find(itemId)
            if (slot < 0) break
            val pushed = src.push(slot, target, remaining)
            if (pushed == 0) break
            moved += pushed
            remaining -= pushed
        }
        return moved
    }
}
