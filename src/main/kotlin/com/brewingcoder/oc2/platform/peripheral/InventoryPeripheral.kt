package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + write a single inventory exposed via NeoForge's `IItemHandler`. The
 * concrete impl (in `block/parts/InventoryPart`) wraps the live capability
 * handle; this interface is what scripts see.
 *
 * Method shape borrowed from CC:Tweaked's inventory peripheral, adjusted for
 * the OC2 multi-peripheral world: push/pull take a *target* / *source* handle
 * (another `InventoryPeripheral`) plus a slot index — scripts don't address by
 * world coordinate.
 */
interface InventoryPeripheral : Peripheral {
    override val kind: String get() = "inventory"

    /** Stable display name — auto-generated (`inv_north_3`) unless the player labeled it. */
    override val name: String

    /** Total slot count of the wrapped inventory. */
    fun size(): Int

    /** Snapshot of the slot at [slot] (1-indexed). Returns null if empty or out of range. */
    fun getItem(slot: Int): ItemSnapshot?

    /**
     * Snapshot of every slot. List length equals [size]; nulls are empty slots.
     * Cheap — single pass over the inventory.
     */
    fun list(): List<ItemSnapshot?>

    /**
     * Move up to [count] items from `this[slot]` into [target]. Returns the
     * number actually moved. Stops on item mismatch or full target.
     */
    fun push(slot: Int, target: InventoryPeripheral, count: Int = 64, targetSlot: Int? = null): Int

    /**
     * Pull up to [count] items from `source[slot]` into `this`. Returns the
     * number actually moved.
     */
    fun pull(source: InventoryPeripheral, slot: Int, count: Int = 64, targetSlot: Int? = null): Int

    /** Find the first slot matching [itemId] (e.g. `"minecraft:diamond"`). 1-indexed; -1 if none. */
    fun find(itemId: String): Int

    /**
     * Permanently void up to [count] items from `this[slot]`. Returns the
     * number actually destroyed. Atomic — no item entity spawned, no copy
     * made; the items cease to exist. Honors the OC2 anti-dupe rule
     * (no create-from-nothing path; this is only the *destroy* leg).
     */
    fun destroy(slot: Int, count: Int): Int

    /** Lightweight item snapshot — id + count. NBT/components deferred (R2). */
    data class ItemSnapshot(val id: String, val count: Int)
}
