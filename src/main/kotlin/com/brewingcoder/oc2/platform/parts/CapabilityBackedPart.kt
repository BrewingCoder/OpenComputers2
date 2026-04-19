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

    /**
     * Override the side of the adjacent block we read the capability from.
     * Empty = use the install face's opposite (the default; matches "I'm
     * looking at the neighbor's face that points at me"). Set to a face
     * serializedName ("north", "up", etc.) to force a specific side — useful
     * for sided machines (furnace top = input, bottom = output, sides = fuel).
     */
    var accessSide: String = ""

    override val options: MutableMap<String, String> = mutableMapOf()

    private var cachedCapability: C? = null

    override fun onAttach(host: PartHost) {
        if (label.isEmpty()) label = host.defaultLabel(typeId)
        cachedCapability = host.lookupCapability(capabilityKey, accessSide.ifBlank { null })
    }

    override fun onNeighborChanged(host: PartHost) {
        cachedCapability = host.lookupCapability(capabilityKey, accessSide.ifBlank { null })
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
        out.putString("accessSide", accessSide)
        out.putString("options", PartOptionsCodec.encode(options))
    }

    override fun loadNbt(input: Part.NbtReader) {
        if (input.has("label")) label = input.getString("label")
        if (input.has("channelId")) channelId = input.getString("channelId")
        if (input.has("accessSide")) accessSide = input.getString("accessSide")
        if (input.has("options")) {
            options.clear()
            options.putAll(PartOptionsCodec.decode(input.getString("options")))
        }
    }

    companion object {
        const val DEFAULT_CHANNEL: String = "default"
    }
}
