package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + craft from one Crafter Part — a programmable wrapper around an
 * adjacent vanilla [net.minecraft.world.level.block.entity.BlockEntity] in a
 * `minecraft:crafting_table`. Holds 18 Recipe Card slots; scripts list what's
 * programmed and order one craft per [craft] call.
 *
 * The crafter is NOT a storage device — it has no internal ingredient
 * inventory. Every [craft] takes a [source] [InventoryPeripheral] which
 * supplies ingredients and receives the output. Idempotent within a script
 * tick (~50ms throttle is enforced server-side).
 *
 * The recipe lookup happens at craft time against [net.minecraft.world.item.crafting.RecipeManager],
 * so a card programmed before a datapack reload still works after — recipe id
 * drift doesn't break the card.
 */
interface CrafterPeripheral : Peripheral {
    override val kind: String get() = "crafter"

    /** Stable display name — auto-generated unless the player labeled it. */
    override val name: String

    /** Total card slot count. Constant 18 in v0; surfaced for forward-compat. */
    fun size(): Int

    /**
     * Snapshot of every card slot. List length equals [size]; nulls are empty
     * slots, [CardSnapshot] entries describe programmed cards (with a best-effort
     * resolved output for UI display).
     */
    fun list(): List<CardSnapshot?>

    /**
     * Run the recipe in card [slot] up to [count] times. Pulls ingredients from
     * [source] and pushes the output back into [source]. Returns the number of
     * crafts that actually completed.
     *
     * Throws when:
     *   - [slot] is out of range or empty
     *   - the card's pattern doesn't match a registered recipe
     *   - [source] doesn't hold enough ingredients (after the first failure,
     *     the call returns the partial count rather than throwing)
     *   - the lease on `this` is held by a different script
     */
    fun craft(slot: Int, count: Int, source: InventoryPeripheral): Int

    /**
     * Registry id of the block this crafter faces (e.g. `"minecraft:crafting_table"`
     * or `"craftingstation:crafting_station"`). Diagnostic — lets scripts confirm
     * which workstation the crafter is bound to without having to peek at the
     * world directly.
     *
     * Null when off-world or the chunk is unloaded.
     */
    fun adjacentBlock(): String?

    /**
     * Lightweight card snapshot. [output] is the recipe's display result at
     * snapshot time — null if the card is blank, the placeholder
     * `"oc2:unmatched"` if the pattern doesn't currently resolve to a recipe.
     * [inputs] is the merged per-cycle ingredient list (item id → count) —
     * empty when the card is blank.
     */
    data class CardSnapshot(
        val slot: Int,
        val output: String?,
        val outputCount: Int,
        val inputs: List<RecipeIngredient> = emptyList(),
    )
}
