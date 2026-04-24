package com.brewingcoder.oc2.platform.peripheral

import com.brewingcoder.oc2.platform.Position

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

    /** World block position of this peripheral. Exposed to scripts as `getLocation()`. */
    val location: Position
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

    /**
     * Filled axis-aligned ellipse. Center at (cx, cy), x-radius [rx], y-radius [ry]
     * in pixels. Use this to compensate for the monitor's non-square pixel aspect
     * (pixels are ~2.22x taller than wide): pass rx ~= ry * 20/9 to get a visually
     * round shape in world space.
     */
    fun fillEllipse(cx: Int, cy: Int, rx: Int, ry: Int, argb: Int)

    // ---- Item icon overlay (composited ABOVE the text grid) ----
    //
    // MC item textures rendered at caller-chosen pixel size, as a top-layer
    // overlay over the text + HD-pixel layers. The monitor keeps a per-frame
    // list of (x, y, sizePx, itemId) tuples that the client-side renderer
    // walks after text. Use [clearIcons] once per render frame before you
    // add fresh overlays — icons do not auto-expire.
    //
    // Icons are intended for dashboard glyphs: "show this machine's primary
    // output", "these are the buckets of fuel I have", etc. They stay fixed
    // to their pixel coordinates and scale independently of the text grid.

    /**
     * Place one MC item texture at pixel-top-left ([x], [y]) with size
     * [wPx] × [hPx] in pixel-buffer coordinates. [itemId] is a vanilla/modded
     * registry id like `"minecraft:redstone"` or `"create:brass_ingot"`;
     * unknown ids render as the missing-texture fallback.
     *
     * Pixel-buffer pixels are NOT square on screen (cells are 20w×9h but
     * each cell is 12×12 px, so a "square" pixel rect renders as a vertical
     * stripe). Callers that want a visually-square icon should pass
     * `wPx ≈ 2.22 × hPx`, or let the `Icon(shape="item")` widget handle it.
     */
    fun drawItem(x: Int, y: Int, wPx: Int, hPx: Int, itemId: String)

    /**
     * Place one MC fluid texture at pixel-top-left ([x], [y]) with size
     * [wPx] × [hPx] in pixel-buffer coordinates. [fluidId] is a vanilla/modded
     * registry id like `"minecraft:water"` or `"mekanism:hydrogen"`; unknown ids
     * render as the missing-texture fallback.
     *
     * Rendered as the fluid's still-texture atlas sprite tinted by the
     * fluid type's client tint color (blue for water, orange for lava, etc.).
     * Same icon overlay layer as [drawItem]; [clearIcons] clears both kinds.
     *
     * Aspect caveat identical to [drawItem].
     */
    fun drawFluid(x: Int, y: Int, wPx: Int, hPx: Int, fluidId: String)

    /**
     * Place one Mekanism chemical texture at pixel-top-left ([x], [y]) with
     * size [wPx] × [hPx] in pixel-buffer coordinates. [chemicalId] is a
     * Mekanism chemical registry id like `"mekanism:hydrogen"`,
     * `"mekanism:oxygen"`, `"mekanism:polonium"`.
     *
     * Soft-dep on Mekanism: when Mekanism is absent the draw is a no-op.
     * Unknown ids render as the missing-texture fallback when Mekanism is
     * present. Rendered as the chemical's icon sprite tinted by the
     * chemical's color. Same icon overlay layer as [drawItem]/[drawFluid];
     * [clearIcons] clears all three kinds.
     *
     * Aspect caveat identical to [drawItem].
     */
    fun drawChemical(x: Int, y: Int, wPx: Int, hPx: Int, chemicalId: String)

    /** Wipe every icon overlay (items + fluids + chemicals). Call once per render
     *  pass before emitting fresh [drawItem]/[drawFluid]/[drawChemical] calls,
     *  unless you want accumulation. */
    fun clearIcons()

    /**
     * Filled annular-arc wedge ("pie-slice ring") — the primitive behind Gauge.
     * Draws every pixel whose normalized distance from (cx,cy) is in the band
     * `[1 - thickness/ry, 1]` AND whose clock-convention angle lies within
     * `[startDeg, startDeg+sweepDeg) mod 360`.
     *
     * Clock convention: 0° = up, 90° = right, 180° = down, 270° = left, sweep
     * direction is clockwise. Use [rx] = [ry] * 20/9 for a visually-round arc
     * in world space (same aspect correction as [fillEllipse]).
     *
     * [thickness] is measured on the Y axis in pixels; X thickness is implicit
     * from the rx/ry ratio so the ring stays uniform around the full sweep.
     * Clamped to `[1, ry]`; if [sweepDeg] ≤ 0 the call is a no-op.
     */
    fun drawArc(cx: Int, cy: Int, rx: Int, ry: Int, thickness: Int, startDeg: Int, sweepDeg: Int, argb: Int)

    // ---- Cell-geometry helpers (engine-level; default impls delegate) ----
    //
    // UI libraries constantly need to reason about the character-cell grid in pixel
    // coordinates — "where does the glyph for cell (col,row) land in pixel space?"
    // and "what's the largest odd cell-band that fits inside this pixel-rect?".
    // Promoting these to the engine so the Lua + JS ports don't each re-derive them.

    /** Cell grid dimensions + pixel-per-cell size. Snapshot — no further marshaling required. */
    data class CellMetrics(val cols: Int, val rows: Int, val pxPerCol: Int, val pxPerRow: Int)

    /** Result of [snapCellRect] — a pixel-rect aligned to the character-cell grid with a centered text row. */
    data class SnappedCellRect(val snappedY: Int, val snappedH: Int, val textRow: Int)

    /** (cols, rows, pxPerCol, pxPerRow). pxPerCol/pxPerRow are `floor(pxW/cols)` / `floor(pxH/rows)`, min 1. */
    fun getCellMetrics(): CellMetrics {
        val (cols, rows) = getSize()
        val (pw, ph) = getPixelSize()
        val pxc = maxOf(1, if (cols > 0) pw / cols else pw)
        val pxr = maxOf(1, if (rows > 0) ph / rows else ph)
        return CellMetrics(cols, rows, pxc, pxr)
    }

    /**
     * Snap a pixel-space vertical band `[y, y+h)` onto the character-cell grid so
     * a label in the middle cell is pixel-centered.
     *
     * Returns `(snappedY, snappedH, textRow)`:
     *   - `snappedY/snappedH` span the largest odd number of cell-rows ≤ `h/pxPerRow`,
     *     centered vertically on the user's midpoint `y + h/2`
     *   - `textRow` is the cell row in the middle of that band
     *
     * Used by Banner, Button, and any widget that needs a background rect with a
     * pixel-centered text label. User's `h` is a HINT — the returned rect may be
     * shorter or shifted by a few pixels to fit the grid.
     */
    fun snapCellRect(y: Int, h: Int): SnappedCellRect {
        val m = getCellMetrics()
        val pxPerRow = m.pxPerRow
        var cellCount = maxOf(1, h / pxPerRow)
        if (cellCount % 2 == 0) cellCount -= 1
        if (cellCount < 1) cellCount = 1
        val snappedH = cellCount * pxPerRow
        val midY = y + h / 2
        val topCellRow = maxOf(0, midY / pxPerRow - cellCount / 2)
        val snappedY = topCellRow * pxPerRow
        val textRow = topCellRow + cellCount / 2
        return SnappedCellRect(snappedY, snappedH, textRow)
    }

    // ---- ARGB color math ----
    //
    // Lua 5.2 has no native bitwise ops; Rhino ES5 treats `|` as signed 32-bit which
    // mangles high-alpha colors. Both language ports re-implemented the same integer
    // divmod trick. One Kotlin impl eliminates the drift risk.

    /** Pack (a, r, g, b) into a single 0xAARRGGBB int. Each channel clamped to 0..255. */
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    /** Brighten each RGB channel by [amt] (0..255), clamped. Alpha unchanged. */
    fun lighten(argbColor: Int, amt: Int): Int {
        val a = (argbColor ushr 24) and 0xFF
        val r = minOf(255, ((argbColor ushr 16) and 0xFF) + amt)
        val g = minOf(255, ((argbColor ushr 8) and 0xFF) + amt)
        val b = minOf(255, (argbColor and 0xFF) + amt)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Halve each RGB channel — used for the `enabled=false` UI dim treatment. Alpha unchanged. */
    fun dim(argbColor: Int): Int {
        val a = (argbColor ushr 24) and 0xFF
        val r = ((argbColor ushr 16) and 0xFF) / 2
        val g = ((argbColor ushr 8) and 0xFF) / 2
        val b = (argbColor and 0xFF) / 2
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---- Text-grid convenience ----

    /** Write [text] at cell (col, row) with the given fg/bg. Folds the 4-call
     *  `setForegroundColor` / `setBackgroundColor` / `setCursorPos` / `write`
     *  sequence used everywhere in UI code. Pass bg=0 for transparent. */
    fun drawText(col: Int, row: Int, text: String, fg: Int, bg: Int) {
        setForegroundColor(fg)
        setBackgroundColor(bg)
        setCursorPos(col, row)
        write(text)
    }

    /** Blank-fill (or repeat-char-fill) a cell-band starting at (col, row) with
     *  [cellCount] copies of [ch]. Used to clear stale glyph cells when a widget's
     *  label shrinks between renders. No-op if cellCount ≤ 0. */
    fun fillText(col: Int, row: Int, cellCount: Int, ch: Char, fg: Int, bg: Int) {
        if (cellCount <= 0) return
        setForegroundColor(fg)
        setBackgroundColor(bg)
        setCursorPos(col, row)
        val sb = StringBuilder(cellCount)
        repeat(cellCount) { sb.append(ch) }
        write(sb.toString())
    }

    // ---- Sub-cell pixel font (digits + '%') ----
    //
    // The text grid is 12-px cell-aligned, so centering a "42%" overlay inside a
    // short Bar or Gauge via setCursorPos is impossible without tearing. Rasterize
    // the glyphs directly into the pixel buffer instead. Frozen charset: '0'..'9'
    // and '%' only. Out-of-charset characters render as gaps.

    /**
     * Rasterize [text] centered on pixel-space point (cx, cy) in [color], using a
     * 5×7 pixel font. Only digits '0'..'9' and '%' are supported; other characters
     * advance the cursor without drawing.
     *
     * Default impl fans out to [setPixel] per lit pixel; BE implementations may
     * override to avoid per-pixel server-thread marshaling.
     */
    fun drawSmallText(cx: Int, cy: Int, text: String, color: Int) {
        if (text.isEmpty()) return
        val totalW = text.length * SMALL_FONT_CHAR_W + (text.length - 1) * SMALL_FONT_GAP
        val x0 = cx - (totalW - 1) / 2
        val y0 = cy - (SMALL_FONT_CHAR_H - 1) / 2
        for ((i, ch) in text.withIndex()) {
            val glyph = SMALL_FONT[ch] ?: continue
            val gx = x0 + i * (SMALL_FONT_CHAR_W + SMALL_FONT_GAP)
            for (row in 0 until SMALL_FONT_CHAR_H) {
                val bits = glyph[row]
                for (col in 0 until SMALL_FONT_CHAR_W) {
                    if ((bits shr (SMALL_FONT_CHAR_W - 1 - col)) and 1 != 0) {
                        setPixel(gx + col, y0 + row, color)
                    }
                }
            }
        }
    }

    companion object {
        const val TOUCH_QUEUE_CAP = 32

        /** 5×7 pixel font — digits + '%'. Each row is a 5-bit pattern, MSB = leftmost px. */
        const val SMALL_FONT_CHAR_W = 5
        const val SMALL_FONT_CHAR_H = 7
        const val SMALL_FONT_GAP = 1
        val SMALL_FONT: Map<Char, IntArray> = mapOf(
            '0' to intArrayOf(14, 17, 17, 17, 17, 17, 14),
            '1' to intArrayOf(4, 12, 4, 4, 4, 4, 14),
            '2' to intArrayOf(14, 17, 1, 2, 4, 8, 31),
            '3' to intArrayOf(14, 17, 1, 6, 1, 17, 14),
            '4' to intArrayOf(2, 6, 10, 18, 31, 2, 2),
            '5' to intArrayOf(31, 16, 30, 1, 1, 17, 14),
            '6' to intArrayOf(14, 16, 16, 30, 17, 17, 14),
            '7' to intArrayOf(31, 1, 2, 4, 8, 8, 8),
            '8' to intArrayOf(14, 17, 17, 14, 17, 17, 14),
            '9' to intArrayOf(14, 17, 17, 15, 1, 1, 14),
            '%' to intArrayOf(25, 26, 2, 4, 8, 11, 19),
        )
    }
}
