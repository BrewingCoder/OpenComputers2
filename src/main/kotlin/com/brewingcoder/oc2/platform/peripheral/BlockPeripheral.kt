package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + harvest the block sitting in front of a [com.brewingcoder.oc2.platform.parts.Part].
 * Mirrors what Integrated Dynamics' Block Reader exposes (id, NBT, light, redstone)
 * with the addition of [harvest] — the block can be broken and its loot moved
 * into a target inventory, satisfying the OC2 anti-dupe rule (block ceases to
 * exist as the items begin to exist; this is a *move* from world to inventory).
 */
interface BlockPeripheral : Peripheral {
    override val kind: String get() = "block"

    override val name: String

    /** Snapshot of the adjacent block. Returns null only if the host is somehow off-world. */
    fun read(): BlockReadout?

    /**
     * Break the adjacent block, route its loot table results into [target].
     * Items that don't fit (target full / no target) drop on the ground at the
     * broken block's position — matches vanilla "block broken, no pickup" behavior.
     *
     * Returns the snapshots of items routed to [target] (NOT items dropped on
     * ground). No-op + empty list if the adjacent block is air or unbreakable.
     *
     * Anti-dupe note: the block ceases to exist atomically with item creation.
     * This is a MOVE from world to inventory; not a copy.
     */
    fun harvest(target: InventoryPeripheral?): List<InventoryPeripheral.ItemSnapshot>

    /** Lightweight snapshot of an in-world block. */
    data class BlockReadout(
        val id: String,         // namespaced block id (e.g. "minecraft:chest"); "minecraft:air" if empty
        val isAir: Boolean,
        val pos: Triple<Int, Int, Int>,
        val lightLevel: Int,    // 0..15
        val redstonePower: Int, // 0..15 — strongest signal feeding this block
        val hardness: Float,    // -1 = unbreakable
        val nbt: String?,       // BE NBT encoded as SNBT, or null if no BE
    )
}
