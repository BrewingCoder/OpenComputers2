package com.brewingcoder.oc2.platform.parts

/**
 * Platform-pure view of the BE that owns a [Part]. Lets [Part] implementations
 * inspect "what's adjacent on my face" and re-resolve their capability handles
 * without importing `net.minecraft.*`.
 *
 * Concrete BE provides this when calling [Part.onAttach] / [Part.onNeighborChanged].
 *
 * Capability lookup is opaque — the seam is `lookup<C>(capabilityKey)` returning
 * a typed handle (or null). Concrete impls translate the key to NeoForge's
 * `BlockCapability<C, Direction>` and call `level.getCapability(...)` with the
 * right side direction.
 */
interface PartHost {
    /** The face this part sits on (north/south/east/west/up/down — string id). */
    val faceId: String

    /** Auto-generated address — `<kind>_<face>_<hostId>`. Used as the default label. */
    fun defaultLabel(typeId: String): String

    /**
     * Resolve a NeoForge capability on the adjacent block. The opaque [key]
     * encodes the capability + side (the face this part sits on). Concrete
     * implementations register the keys they care about — see
     * `block/parts/AdapterPartHost`.
     *
     * Returns null when the adjacent block doesn't expose that capability.
     */
    fun <C : Any> lookupCapability(key: CapabilityKey<C>): C?

    /**
     * Read the redstone signal level (0–15) feeding into this part's face. Used
     * by RedstonePart. Pulled out of capability dispatch because vanilla
     * redstone isn't a NeoForge capability.
     */
    fun readRedstoneSignal(): Int

    /** Set the redstone signal this part emits onto its face. */
    fun writeRedstoneSignal(level: Int)
}

/**
 * Phantom-typed key for capability lookup. Concrete impls register one of these
 * for each capability they want exposed (Item / Fluid / Energy). Keeping this
 * opaque to the [Part] interface lets capability registration live in the
 * MC-coupled layer.
 */
class CapabilityKey<C : Any>(val name: String) {
    override fun toString(): String = "CapabilityKey($name)"
}
