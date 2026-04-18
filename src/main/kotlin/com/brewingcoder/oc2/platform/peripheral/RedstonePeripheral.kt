package com.brewingcoder.oc2.platform.peripheral

/**
 * Read + write a redstone signal on one face of an Adapter. Mirrors CC:Tweaked's
 * `redstone` peripheral surface. Levels are 0–15.
 *
 * - [getInput] reads what the adjacent block is feeding INTO our face
 * - [setOutput] sets the level we're emitting OUT of our face
 *
 * Output is sticky — set once, holds until changed or the part is removed.
 */
interface RedstonePeripheral : Peripheral {
    override val kind: String get() = "redstone"

    /** Stable display name — auto-generated unless labeled. */
    val name: String

    /** Signal the adjacent block is feeding into this face (0–15). */
    fun getInput(): Int

    /** Signal this face is currently emitting (0–15). */
    fun getOutput(): Int

    /** Emit [level] (clamped to 0–15) on this face until changed. */
    fun setOutput(level: Int)
}
