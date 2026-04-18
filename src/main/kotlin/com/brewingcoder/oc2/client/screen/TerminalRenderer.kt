package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

/**
 * Custom glyph-grid renderer for the Computer terminal area.
 *
 * Why we don't use MC's Font system:
 *   - MC TTF fonts get rasterized once at fixed pixel size, then blitted
 *     through GUI scale → softness at small sizes
 *   - MC bitmap fonts via FontManager are crisp but inflexible (can't scale,
 *     can't do per-cell colors, no cursor/ANSI semantics)
 *   - Real terminal features we'll need (cursor blink, ANSI color codes,
 *     scrollback, text selection, attributes) want a per-cell render model,
 *     not a string-based one
 *
 * What this gives us:
 *   - Pixel-perfect 1:1 atlas blits (sharp at any GUI scale)
 *   - Per-cell color (foundation for ANSI color support)
 *   - Direct control over cursor rendering
 *   - Future: blink, attributes, scrollback navigation
 *
 * Atlas convention:
 *   - 16 cols × 16 rows = 256 ASCII cells
 *   - Cell at (col, row) = codepoint (row*16 + col)
 *   - Each cell is [cellW × cellH] pixels in the source PNG
 *   - The PNG lives at assets/oc2/textures/font/<name>.png
 */
class TerminalRenderer(
    private val atlas: ResourceLocation,
    val cellW: Int,
    val cellH: Int,
    private val atlasCols: Int = 16,
    private val atlasRows: Int = 16,
) {
    /** Total atlas dimensions in pixels. */
    private val atlasW = cellW * atlasCols
    private val atlasH = cellH * atlasRows

    /**
     * Draw a single line of text starting at (x, y). Each character occupies
     * one cell of [cellW × cellH] pixels (pre-GUI-scale).
     *
     * @param color ARGB int, e.g. 0xFF33FF66 for the CRT green
     */
    fun drawLine(graphics: GuiGraphics, x: Int, y: Int, text: String, color: Int) {
        // GuiGraphics.setColor multiplies subsequent blits — we use that to tint
        // the white-on-transparent atlas glyphs to our terminal color.
        val a = ((color ushr 24) and 0xFF) / 255f
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        graphics.setColor(r, g, b, a)
        try {
            text.forEachIndexed { i, c ->
                val code = c.code
                if (code !in 0x20..0xFF) return@forEachIndexed
                val srcCol = code % atlasCols
                val srcRow = code / atlasCols
                val u = (srcCol * cellW).toFloat()
                val v = (srcRow * cellH).toFloat()
                val dx = x + i * cellW
                graphics.blit(
                    atlas,
                    dx, y,
                    u, v,
                    cellW, cellH,
                    atlasW, atlasH,
                )
            }
        } finally {
            graphics.setColor(1f, 1f, 1f, 1f)
        }
    }

    /**
     * Draw multiple lines stacked vertically. Returns the y-coord just below
     * the last line drawn (useful for cursor positioning, "insert next line
     * here" math).
     */
    fun drawLines(graphics: GuiGraphics, x: Int, y: Int, lines: List<String>, color: Int, lineSpacing: Int = 1): Int {
        var cy = y
        val rowH = cellH + lineSpacing
        for (line in lines) {
            drawLine(graphics, x, cy, line, color)
            cy += rowH
        }
        return cy
    }

    /** Pixel width of a string in this terminal's font. */
    fun width(text: String): Int = text.length * cellW

    companion object {
        /** Atlas reference — full `textures/...` path required for raw blit (no auto-prefix). */
        val DEFAULT_ATLAS: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/font/terminal_atlas.png")
    }
}
