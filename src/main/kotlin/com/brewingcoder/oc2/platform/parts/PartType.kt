package com.brewingcoder.oc2.platform.parts

/**
 * Registry entry for a [Part] kind. Knows the type id (used in NBT + as the
 * Peripheral kind) and how to mint fresh instances.
 *
 * Registration happens at mod init via [PartRegistry.register]. Item ↔ part
 * binding is one-to-one in v0: each [PartType] has exactly one part item that
 * spawns it on right-click (see `block/parts/PartItems`).
 */
interface PartType {
    /** Stable id — `"inventory"`, `"redstone"`, etc. Persisted to NBT. */
    val id: String

    /** Fresh instance — called when the player installs the part item on a face. */
    fun create(): Part
}

/**
 * Static directory of every registered [PartType]. Filled by `block/parts/PartTypes`
 * during mod init; queried by the Adapter BE on NBT load to rehydrate parts and
 * by part items on install.
 */
object PartRegistry {
    private val byId: MutableMap<String, PartType> = mutableMapOf()

    fun register(type: PartType) {
        require(!byId.containsKey(type.id)) { "duplicate PartType id: ${type.id}" }
        byId[type.id] = type
    }

    fun get(id: String): PartType? = byId[id]
    fun all(): Collection<PartType> = byId.values

    /** Test hook — wipes the registry. */
    internal fun clearForTest() = byId.clear()
}
