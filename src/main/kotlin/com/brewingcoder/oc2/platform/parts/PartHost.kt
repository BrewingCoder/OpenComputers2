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

    /** World block position of the adapter hosting this part. */
    val location: com.brewingcoder.oc2.platform.Position

    /** Auto-generated address — `<kind>_<face>_<hostId>`. Used as the default label. */
    fun defaultLabel(typeId: String): String

    /**
     * Resolve a NeoForge capability on the adjacent block.
     *
     * @param sideOverride face serializedName ("north", "up", etc.) to read
     *   from a specific side of the adjacent block. null/empty = use the
     *   install face's opposite (i.e., the side of the neighbor pointed
     *   back at us — the natural default). Useful for sided machines like
     *   furnaces where top/bottom/sides expose different slots.
     *
     * Returns null when the adjacent block doesn't expose that capability.
     */
    fun <C : Any> lookupCapability(key: CapabilityKey<C>, sideOverride: String? = null): C?

    /**
     * Read the redstone signal level (0–15) feeding into this part's face. Used
     * by RedstonePart. Pulled out of capability dispatch because vanilla
     * redstone isn't a NeoForge capability.
     */
    fun readRedstoneSignal(): Int

    /** Set the redstone signal this part emits onto its face. */
    fun writeRedstoneSignal(level: Int)

    /**
     * Snapshot of the adjacent block (the one this part faces). Null if off-world.
     * Used by [com.brewingcoder.oc2.block.parts.BlockPart]. Server-thread safe
     * — implementations marshal internally.
     */
    fun readAdjacentBlock(): com.brewingcoder.oc2.platform.peripheral.BlockPeripheral.BlockReadout?

    /**
     * Break the adjacent block, route its loot table drops into [target] (an
     * [com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral]) — anything
     * that doesn't fit drops on the ground at the broken block's position.
     * Returns snapshots of items routed *into* the inventory.
     *
     * Server-thread only; impls marshal internally so script callers (running on
     * worker threads) don't need to.
     */
    fun harvestAdjacentBlock(
        target: com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral?,
    ): List<com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot>

    /**
     * Live BlockEntity adjacent to this part's face. Returns [Any]? so this
     * interface stays MC-import-free; concrete consumers (BridgePart and its
     * ProtocolAdapters in `block/bridge/`) cast to `BlockEntity`.
     *
     * Returns null when the adjacent block has no BlockEntity, or when called
     * off-thread / during world unload. **Server-thread access only** — the
     * BridgePart contract requires its peripheral methods to run on the server
     * thread (which the Adapter BE marshals).
     */
    fun adjacentBlockEntity(): Any?

    /**
     * Registry id of the adjacent block ("minecraft:crafting_table" etc.).
     * Lightweight — does NOT touch the BE or its NBT. Returns null when off-world
     * or off-thread. Used by parts whose peripheral resolution depends on the
     * exact adjacent block kind (CrafterPart wants only crafting tables).
     */
    fun adjacentBlockId(): String?

    /**
     * The server [net.minecraft.world.level.Level] hosting this part. Returned
     * as [Any]? so this interface stays MC-import-free; concrete consumers cast
     * to `ServerLevel`. Null when off-world (chunk unloaded etc).
     *
     * Distinct from [adjacentBlockEntity] — the latter returns null for blocks
     * that have no BlockEntity (e.g. vanilla `crafting_table`), whereas this
     * accessor stays valid as long as the adapter is loaded. Use this when you
     * need recipe-manager / registry access regardless of the neighbor's BE.
     */
    fun serverLevel(): Any? = null

    /**
     * Whether the adjacent block carries [tagId] (a namespaced tag id like
     * `c:player_workstations/crafting_tables`). Lets parts gate on tags
     * instead of exact block ids, so modded equivalents (e.g. `craftingstation:
     * crafting_station`) are accepted automatically when the mod tags itself.
     *
     * Returns false when off-world or off-thread.
     */
    fun adjacentBlockHasTag(tagId: String): Boolean = false
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
