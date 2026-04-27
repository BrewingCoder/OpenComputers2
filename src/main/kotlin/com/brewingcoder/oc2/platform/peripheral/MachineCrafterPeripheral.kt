package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + craft from one Machine Crafter Part — a programmable wrapper around
 * an adjacent machine that exposes an [net.neoforged.neoforge.items.IItemHandler]
 * (Mekanism processors, Create mixers/pressers, vanilla furnaces, anything
 * tagged similarly). Holds 18 Recipe Card slots; each card stamps an
 * "ingredients → output" intent the Machine Crafter can run on demand.
 *
 * Unlike [CrafterPeripheral] (which delegates to the vanilla
 * [net.minecraft.world.item.crafting.RecipeManager]), this peripheral does NOT
 * understand any specific machine's recipes — there is no universal cross-mod
 * recipe registry. The card stamps the *expected output* manually; the part
 * just routes ingredients into the machine's input handler and pulls finished
 * items back into the source inventory.
 *
 * Optional **per-recipe blocking** mode (matches AE2/RS/ID semantics): when
 * the card's `blocking` flag is set, [craft] waits until the machine's input
 * slots are empty (i.e. the previous craft has been consumed) before injecting
 * the next batch — preventing recipe-overlap conflicts. Non-blocking cards
 * fire-and-forget every cycle.
 */
interface MachineCrafterPeripheral : Peripheral {
    override val kind: String get() = "machine_crafter"

    /** Stable display name — auto-generated unless the player labeled it. */
    override val name: String

    /** Total card slot count. Constant 18 in v0; surfaced for forward-compat. */
    fun size(): Int

    /**
     * Snapshot of every card slot. List length equals [size]; nulls are empty
     * slots, [CardSnapshot] entries describe programmed cards (with the manually-
     * stamped output and the optional fluid input id+mB).
     */
    fun list(): List<CardSnapshot?>

    /**
     * Run the machine recipe in card [slot] up to [count] times. Pulls
     * ingredients from [source] (an [InventoryPeripheral]) and pushes them to
     * the machine's input handler; pulls finished items back into [source].
     *
     * If the card carries a fluid-input requirement and [fluidSource] is
     * non-null, drains the corresponding fluid from [fluidSource] and pushes
     * it into the machine's [net.neoforged.neoforge.fluids.capability.IFluidHandler]
     * (when the machine exposes one).
     *
     * Returns the number of crafts that actually completed (i.e. ingredients
     * were extracted, pushed, and at least one output unit was pulled back).
     *
     * Throws when:
     *   - [slot] is out of range or empty
     *   - the card is in [com.brewingcoder.oc2.item.RecipePattern.Mode.TABLE]
     *     mode (use [CrafterPeripheral] for grid recipes)
     *   - the lease on `this` is held by a different script
     */
    fun craft(slot: Int, count: Int, source: InventoryPeripheral, fluidSource: FluidPeripheral? = null): Int

    /**
     * Registry id of the block this machine crafter faces. Diagnostic — same
     * accessor surface as [CrafterPeripheral.adjacentBlock]. Null when off-world
     * or the chunk is unloaded.
     */
    fun adjacentBlock(): String?

    /**
     * Lightweight card snapshot. [output] is the user-stamped result item id
     * (null when the card is blank); [outputCount] is the stamped result count.
     * [inputs] is the merged per-cycle ingredient list (item id → count) — empty
     * when the card is blank. [fluidIn] / [fluidInMb] describe the optional
     * fluid-input requirement (null/0 when the recipe is item-only).
     */
    data class CardSnapshot(
        val slot: Int,
        val output: String?,
        val outputCount: Int,
        val fluidIn: String?,
        val fluidInMb: Int,
        val blocking: Boolean,
        val inputs: List<RecipeIngredient> = emptyList(),
    )
}
