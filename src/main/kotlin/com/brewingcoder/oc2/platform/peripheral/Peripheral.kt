package com.brewingcoder.oc2.platform.peripheral

/**
 * Marker interface for any device a script can call methods on. Each subtype
 * defines its own method surface (see [MonitorPeripheral]). The script-side
 * `peripheral.find(kind)` API returns a value of the specific subtype, looked
 * up via the host computer's channel registry.
 *
 * Rule D: lives in `platform/`, no MC imports. BE classes (MC-coupled) implement
 * these interfaces — the BE is the impl, the platform-pure interface is the
 * contract scripts see.
 */
interface Peripheral {
    /** Stable kind identifier — `"monitor"`, `"printer"`, etc. */
    val kind: String
}

/**
 * Display-and-text-input device. Scripts get a handle via
 * `peripheral.find("monitor")`. Mirrors the small subset of CC:Tweaked's
 * monitor API we shipped in v0:
 *   - `write(text)` — append at cursor, advancing horizontally; wraps + scrolls
 *   - `setCursorPos(col, row)` — 0-indexed
 *   - `clear()` — wipe the buffer + reset cursor
 *   - `getSize()` — total dimensions in characters across the whole group
 *
 * Multi-block: a 2x2 group exposes a single 40-col × 20-row surface. Scripts
 * never see individual blocks — the master block coalesces the group.
 */
interface MonitorPeripheral : Peripheral {
    override val kind: String get() = "monitor"

    fun write(text: String)
    fun setCursorPos(col: Int, row: Int)
    fun clear()
    /** Returns (cols, rows) for the full multi-block surface. */
    fun getSize(): Pair<Int, Int>

    /**
     * Set the foreground (text) color used by subsequent [write] calls.
     * [color] is ARGB int — `0xFFRRGGBB`. Pass `0xFF000000` for opaque black,
     * `0xFFD4D4D4` for the default editor.foreground color.
     */
    fun setForegroundColor(color: Int)

    /**
     * Set the background (cell-fill) color used by subsequent [write] calls.
     * Pass `0` (fully transparent) to leave the bg unset — the monitor's panel
     * color shows through.
     */
    fun setBackgroundColor(color: Int)

    /**
     * Drain the queue of touch events that have accumulated since the last call.
     * Each event records `(col, row, playerName)` for one right-click on the
     * monitor's front face.
     *
     * v0 polling model: scripts must explicitly call this — there's no event
     * loop yet to trigger handlers asynchronously. A "real" `onTouch(handler)`
     * API awaits cooperative scheduling (followups doc).
     *
     * The queue is bounded at [TOUCH_QUEUE_CAP] events; older touches drop off
     * if scripts don't poll often enough.
     */
    fun pollTouches(): List<TouchEvent>

    data class TouchEvent(val col: Int, val row: Int, val playerName: String)

    companion object {
        const val TOUCH_QUEUE_CAP = 32
    }
}
