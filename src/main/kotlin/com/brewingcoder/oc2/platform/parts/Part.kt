package com.brewingcoder.oc2.platform.parts

import com.brewingcoder.oc2.platform.peripheral.Peripheral

/**
 * One installed part on one face of an Adapter (or part-hosting block). Each
 * part is independently addressable as a [Peripheral] on the host's wifi
 * channel — scripts call `peripheral.find("inventory")` etc. without knowing
 * which adapter or face it lives on.
 *
 * Lifecycle (driven by the host BE):
 *   1. [PartType.create] mints an instance when the player installs a part item
 *   2. [onAttach] runs after the BE has wired the part into its face slot —
 *      capability lookups happen here and on neighbor changes
 *   3. [asPeripheral] is queried whenever scripts enumerate peripherals
 *   4. [onDetach] runs before the part is dropped/removed
 *   5. [saveNbt] / [loadNbt] persist per-part state across reloads
 *
 * Rule D: lives in `platform/`, no `net.minecraft.*` types in the *interface*.
 * Concrete implementations under `block/parts/` may import MC freely; the
 * platform contract just exchanges opaque snapshot data via [PartHost].
 */
interface Part {
    /** Stable type id — `"inventory"`, `"redstone"`, etc. Maps to [PartType.id]. */
    val typeId: String

    /** Human-readable label for `peripheral.list().name` lookups. May be auto-generated. */
    var label: String

    /**
     * Wifi channel this part advertises on. Per-part — two parts on the same
     * adapter can sit on different channels. The adapter is just a physical
     * mount; routing is per-peripheral.
     */
    var channelId: String

    /**
     * Called once after the host BE has wired this part into its face slot.
     * Implementations should resolve their first capability lookup here. Re-runs
     * on `neighborChanged` via [onNeighborChanged].
     */
    fun onAttach(host: PartHost)

    /** Re-resolve capabilities when the adjacent block changed. Cheap; called often. */
    fun onNeighborChanged(host: PartHost) {}

    /**
     * Drop everything. Called before the part is detached (player removed it,
     * adapter broken, world unloading). Must release any cached capability
     * handles to avoid leaking BE references.
     */
    fun onDetach() {}

    /**
     * Per-tick hook. Called from the host BE's tick if the implementation needs
     * polling (energy meters, redstone level changes, etc.). Default no-op.
     */
    fun tick(host: PartHost) {}

    /**
     * Returns the [Peripheral] this part exposes to scripts. Returning null
     * means "not currently usable" (e.g., adjacent block doesn't have the
     * required capability) — `peripheral.find` skips it.
     */
    fun asPeripheral(): Peripheral?

    /**
     * Serialize per-instance state. The label is persisted by the host BE for
     * every part, so implementations only need to write *additional* fields
     * (cached display values, configuration, etc.). Pass-through impls can
     * leave this empty.
     */
    fun saveNbt(out: NbtWriter) {}

    /** Inverse of [saveNbt]. */
    fun loadNbt(input: NbtReader) {}

    /**
     * Tiny abstraction over [net.minecraft.nbt.CompoundTag] so the [Part]
     * interface stays platform-pure. Concrete BE wires a real CompoundTag
     * into these.
     */
    interface NbtWriter {
        fun putString(key: String, value: String)
        fun putInt(key: String, value: Int)
        fun putBoolean(key: String, value: Boolean)
    }
    interface NbtReader {
        fun getString(key: String): String
        fun getInt(key: String): Int
        fun getBoolean(key: String): Boolean
        fun has(key: String): Boolean
    }
}
