package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + transfer fluids on a single tank exposed via NeoForge's
 * `IFluidHandler`. Surface mirrors [InventoryPeripheral] for symmetry —
 * scripts that grok inventories grok fluids.
 */
interface FluidPeripheral : Peripheral {
    override val kind: String get() = "fluid"

    val name: String

    /** Number of internal tanks. */
    fun tanks(): Int

    /** Snapshot of the tank at [tank] (1-indexed). Returns null if empty. */
    fun getFluid(tank: Int): FluidSnapshot?

    /** Snapshot of every tank. List length equals [tanks]. */
    fun list(): List<FluidSnapshot?>

    /**
     * Move up to [amount] millibuckets of fluid from this tank into [target].
     * Returns the number of mB actually moved.
     */
    fun push(target: FluidPeripheral, amount: Int = 1000): Int

    /** Inverse — pull from [source] into our first available tank. */
    fun pull(source: FluidPeripheral, amount: Int = 1000): Int

    /**
     * Permanently void up to [amount] mB from any tank. Returns mB actually
     * destroyed. The fluid does not become a world entity, does not enter
     * another handler — it simply ceases to exist.
     */
    fun destroy(amount: Int): Int

    /** Lightweight snapshot — fluid id + amount in mB. */
    data class FluidSnapshot(val id: String, val amount: Int)
}
