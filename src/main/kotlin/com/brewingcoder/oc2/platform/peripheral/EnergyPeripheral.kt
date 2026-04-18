package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + transfer Forge Energy (FE) on a single energy buffer exposed via
 * NeoForge's `IEnergyStorage`. Read-only when the adjacent block is a generator
 * (extract = false); transfer-capable when both sides agree.
 */
interface EnergyPeripheral : Peripheral {
    override val kind: String get() = "energy"

    val name: String

    /** Currently stored energy (FE). */
    fun stored(): Int

    /** Total capacity (FE). */
    fun capacity(): Int

    /**
     * Pull up to [amount] FE from [source] into our buffer. Returns the amount
     * actually transferred. Subject to the source's `canExtract` and our
     * `canReceive`.
     */
    fun pull(source: EnergyPeripheral, amount: Int = Int.MAX_VALUE): Int

    /** Push up to [amount] FE from us into [target]. */
    fun push(target: EnergyPeripheral, amount: Int = Int.MAX_VALUE): Int
}
