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
    /** [write] then a newline (cursor wraps to col 0 of next row). Convenience. */
    fun println(text: String) { write(text); write("\n") }

    fun setCursorPos(col: Int, row: Int)
    /** Returns (col, row) — the position the next [write] will start at. */
    fun getCursorPos(): Pair<Int, Int>

    fun clear()
    /** Returns (cols, rows) for the full multi-block surface. */
    fun getSize(): Pair<Int, Int>

    /**
     * Set the foreground (text) color used by subsequent [write] calls.
     * [color] is ARGB int — `0xFFRRGGBB`. Pass `0xFF000000` for opaque black,
     * `0xFFD4D4D4` for the default editor.foreground color.
     */
    fun setForegroundColor(color: Int)
    /** CC:Tweaked-aligned alias for [setForegroundColor]. */
    fun setTextColor(color: Int) { setForegroundColor(color) }

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

    /**
     * Touch event from right-clicking the monitor face. [col]/[row] are the
     * character-cell coordinate the click landed in (legacy); [px]/[py] are
     * the pixel-grid coordinate (HD-mode hit-testing for graphical buttons).
     */
    data class TouchEvent(val col: Int, val row: Int, val px: Int, val py: Int, val playerName: String)

    // ---- HD pixel-buffer API (composited UNDER the text grid) ----
    //
    // The monitor exposes a per-pixel ARGB framebuffer alongside the legacy
    // text grid. Scripts can paint arbitrary graphics: gradient bars, filled
    // rectangles, lines, circles. Composite order: pixel buffer first, then
    // text on top — text remains the focal information layer with graphics
    // as the supporting surface.
    //
    // All coordinates are in PIXELS within the group's full surface (top-left
    // origin). Use [getPixelSize] to query the pixel dimensions.
    //
    // All colors are ARGB ints — `0xFFRRGGBB`. Alpha 0 is fully transparent
    // (lets the underlying block face show through, not lower-z monitor pixels).

    /** Pixel dimensions of the full group surface — (pxWidth, pxHeight). */
    fun getPixelSize(): Pair<Int, Int>

    /** Wipe the entire pixel buffer to [argb]. Pass 0 for transparent. */
    fun clearPixels(argb: Int)

    /** Paint one pixel. Bounds-clipped silently. */
    fun setPixel(x: Int, y: Int, argb: Int)

    /** Filled axis-aligned rectangle. Bounds-clipped silently. */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, argb: Int)

    /** Outlined rectangle. [thickness] in pixels (≥1). */
    fun drawRectOutline(x: Int, y: Int, w: Int, h: Int, argb: Int, thickness: Int = 1)

    /** Bresenham line from (x1,y1) to (x2,y2). Bounds-clipped silently. */
    fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int)

    /**
     * Vertical gradient fill. Linear interpolation in ARGB space — pass
     * `0xFF008000` to `0xFF00FF00` for a dark→bright green energy bar effect.
     */
    fun drawGradientV(x: Int, y: Int, w: Int, h: Int, topArgb: Int, bottomArgb: Int)

    /** Filled circle. Center at (cx, cy), radius [r] (pixels). */
    fun fillCircle(cx: Int, cy: Int, r: Int, argb: Int)

    companion object {
        const val TOUCH_QUEUE_CAP = 32
    }
}
