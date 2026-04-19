package com.brewingcoder.oc2.platform.parts

import com.brewingcoder.oc2.platform.peripheral.Peripheral

/**
 * Base class for parts whose entire functionality comes from looking up a
 * single NeoForge block capability on the adjacent block. Inventory (`IItemHandler`),
 * Fluid (`IFluidHandler`), and Energy (`IEnergyStorage`) all collapse to this
 * pattern — the only difference is the capability key and the [Peripheral] that
 * wraps it.
 *
 * Subclass responsibilities:
 *   - declare [typeId] and [capabilityKey]
 *   - implement [wrapAsPeripheral] — turn the resolved capability into a Peripheral
 *
 * This base handles:
 *   - lookup + cache on attach + neighbor change
 *   - returning null from [asPeripheral] when no capability is present
 *   - persistent label (the only NBT every part needs)
 *
 * Pattern is the reuse Scott asked for: each new capability-backed kind is a
 * ~10-line subclass.
 */
abstract class CapabilityBackedPart<C : Any>(
    override val typeId: String,
    private val capabilityKey: CapabilityKey<C>,
) : Part {

    override var label: String = ""
    override var channelId: String = DEFAULT_CHANNEL

    private var cachedCapability: C? = null

    override fun onAttach(host: PartHost) {
        if (label.isEmpty()) label = host.defaultLabel(typeId)
        cachedCapability = host.lookupCapability(capabilityKey)
    }

    override fun onNeighborChanged(host: PartHost) {
        cachedCapability = host.lookupCapability(capabilityKey)
    }

    override fun onDetach() {
        cachedCapability = null
    }

    /** Live capability handle, or null if the adjacent block doesn't expose it. */
    protected fun capability(): C? = cachedCapability

    final override fun asPeripheral(): Peripheral? {
        val cap = cachedCapability ?: return null
        return wrapAsPeripheral(cap)
    }

    /** Build the script-facing peripheral around [cap]. Called every time `peripheral.find` runs. */
    protected abstract fun wrapAsPeripheral(cap: C): Peripheral

    override fun saveNbt(out: Part.NbtWriter) {
        out.putString("label", label)
        out.putString("channelId", channelId)
    }

    override fun loadNbt(input: Part.NbtReader) {
        if (input.has("label")) label = input.getString("label")
        if (input.has("channelId")) channelId = input.getString("channelId")
    }

    companion object {
        const val DEFAULT_CHANNEL: String = "default"
    }
}
