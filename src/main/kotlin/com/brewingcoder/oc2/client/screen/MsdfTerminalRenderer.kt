package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f

/**
 * Vector-quality terminal text renderer powered by an MSDF (Multi-channel
 * Signed Distance Field) glyph atlas. Drop-in API replacement for the
 * bitmap [TerminalRenderer] — same `drawLine` / `drawLines` / `width`
 * surface; switching is a one-line constant change in [ComputerScreen].
 *
 * Why MSDF, recap from `docs/12-followups.md`:
 *   - one small atlas (256×256 here) renders crisp at any pixel size
 *   - sharp corners preserved (median of 3 channels), unlike single-channel SDF
 *   - GPU-efficient: 4 vertices + 1 sample per glyph
 *
 * Atlas + metadata are baked offline by `msdf-atlas-gen v1.3` (see
 * `tools/build_msdf_atlas.sh` for the recipe). Both load once at static init.
 *
 * Per-call uniform: [MsdfShaders.MSDF_TEXT]'s `ScreenPxRange` is set so the
 * edge smoothstep stays exactly 1 on-screen pixel wide regardless of how big
 * we render. Without this, large text gets blurry edges and tiny text gets
 * hard aliasing.
 */
class MsdfTerminalRenderer(
    /** Target line height in screen pixels. Glyph quads scale to fit. */
    val pxPerEm: Float,
) {
    private val metadata: FontMetadata = METADATA
    val cellH: Int get() = (pxPerEm * metadata.lineHeight).toInt().coerceAtLeast(1)

    /** Approximation of monospace cell width; JBMono advance is uniform at 0.6em. */
    private val cellW: Float = pxPerEm * 0.6f

    /**
     * Draw a single line of text at (x, y). [color] is ARGB — alpha multiplied
     * by the MSDF coverage to give edge anti-aliasing.
     */
    fun drawLine(graphics: GuiGraphics, x: Int, y: Int, text: String, color: Int) {
        val shader = MsdfShaders.get() ?: return  // shader not loaded yet — first frames may skip
        // ScreenPxRange must be expressed in *actual screen pixels* the smoothstep
        // covers, so we multiply by MC's GUI scale (typically 2-4×). Without this
        // factor, small pxPerEm values produce a sub-pixel smoothstep window that
        // bleeds across atlas-cell boundaries — visible as a gray halo / ghosting
        // around every glyph. MSDF needs a screen-pixel window of >= 2 for clean edges.
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        val screenPxRange = metadata.distanceRange * pxPerEm * guiScale / metadata.atlasSize
        shader.getUniform("ScreenPxRange")?.set(screenPxRange)

        val source = graphics.bufferSource()
        val buffer = source.getBuffer(MsdfShaders.MSDF_TEXT)
        val matrix = graphics.pose().last().pose()
        drawLineToBuffer(buffer, matrix, x.toFloat(), y.toFloat(), text, color, pxPerEm)
        source.endBatch(MsdfShaders.MSDF_TEXT)
    }

    /**
     * Lower-level draw: emits glyph quads into [buffer] at (x, y), in whatever
     * coordinate space the matrix transforms into. Used by both the GUI path
     * (above) and the world-space monitor renderer.
     *
     * Caller is responsible for:
     *   - setting `ScreenPxRange` on the MSDF shader before this is invoked
     *   - calling `bufferSource.endBatch(...)` after the draw to flush
     *
     * [units] is the "pixel size" of one em in the matrix's coordinate space.
     * The GUI passes its [pxPerEm]; the world renderer passes a small fraction
     * (because 1 em ~= 1/12 of a world-block face).
     */
    fun drawLineToBuffer(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        x: Float, y: Float,
        text: String,
        color: Int,
        units: Float,
    ) {
        val a = ((color ushr 24) and 0xFF)
        val r = ((color ushr 16) and 0xFF)
        val g = ((color ushr 8) and 0xFF)
        val b = (color and 0xFF)

        val baselineY = y + units * metadata.ascender
        var cursorX = x

        for (ch in text) {
            val glyph = metadata.glyphs[ch.code]
            if (glyph != null && glyph.atlasBounds != null && glyph.planeBounds != null) {
                val pb = glyph.planeBounds
                val ab = glyph.atlasBounds

                val x0 = cursorX + pb.left * units
                val x1 = cursorX + pb.right * units
                val y0 = baselineY - pb.top * units
                val y1 = baselineY - pb.bottom * units

                val u0 = ab.left / metadata.atlasWidth
                val u1 = ab.right / metadata.atlasWidth
                val v0 = 1f - (ab.top / metadata.atlasHeight)
                val v1 = 1f - (ab.bottom / metadata.atlasHeight)

                buffer.addVertex(matrix, x0, y1, 0f).setUv(u0, v1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y1, 0f).setUv(u1, v1).setColor(r, g, b, a)
                buffer.addVertex(matrix, x1, y0, 0f).setUv(u1, v0).setColor(r, g, b, a)
                buffer.addVertex(matrix, x0, y0, 0f).setUv(u0, v0).setColor(r, g, b, a)
            }
            cursorX += (glyph?.advance ?: 0.6f) * units
        }
    }

    /** Public read of font metrics for the world renderer's layout math. */
    val fontLineHeight: Float get() = metadata.lineHeight
    val fontAscender: Float get() = metadata.ascender
    val fontDistanceRange: Float get() = metadata.distanceRange
    val fontAtlasSize: Float get() = metadata.atlasSize

    /** Draw multiple lines stacked vertically. Returns the y-coord just below the last line. */
    fun drawLines(graphics: GuiGraphics, x: Int, y: Int, lines: List<String>, color: Int, lineSpacing: Int = 1): Int {
        var cy = y
        val rowH = cellH + lineSpacing
        for (line in lines) {
            drawLine(graphics, x, cy, line, color)
            cy += rowH
        }
        return cy
    }

    /** Pixel width of [text] in this font. Approximate (uses JBMono's uniform 0.6em advance). */
    fun width(text: String): Int = (text.length * cellW).toInt()

    // ---------- font metadata model + loader ----------

    private data class Bounds(val left: Float, val bottom: Float, val right: Float, val top: Float)
    private data class Glyph(
        val unicode: Int,
        val advance: Float,
        val planeBounds: Bounds?,   // em units, baseline-relative
        val atlasBounds: Bounds?,   // pixel coords inside the atlas PNG
    )
    private data class FontMetadata(
        val distanceRange: Float,
        val atlasSize: Float,        // em-size used when generating
        val atlasWidth: Float,
        val atlasHeight: Float,
        val lineHeight: Float,
        val ascender: Float,
        val descender: Float,
        val glyphs: Map<Int, Glyph>,
    )

    companion object {
        /** Where the metadata JSON lives on the resource path. */
        private val METADATA_PATH: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "font_metadata/jbmono_msdf.json")

        /** Loaded once on first access — atlas + metadata are immutable per build. */
        private val METADATA: FontMetadata by lazy { loadMetadata() }

        private fun loadMetadata(): FontMetadata {
            val rm = Minecraft.getInstance().resourceManager
            val resource = rm.getResource(METADATA_PATH).orElseThrow {
                IllegalStateException("MSDF metadata missing at $METADATA_PATH")
            }
            val text = resource.openAsReader().use { it.readText() }
            val root = JsonParser.parseString(text).asJsonObject

            val atlas = root.getAsJsonObject("atlas")
            val metrics = root.getAsJsonObject("metrics")
            val glyphs = mutableMapOf<Int, Glyph>()
            for (g in root.getAsJsonArray("glyphs")) {
                val o = g.asJsonObject
                val unicode = o.get("unicode").asInt
                val advance = o.get("advance").asFloat
                val planeBounds = o.getAsJsonObject("planeBounds")?.let { boundsOf(it) }
                val atlasBounds = o.getAsJsonObject("atlasBounds")?.let { boundsOf(it) }
                glyphs[unicode] = Glyph(unicode, advance, planeBounds, atlasBounds)
            }

            return FontMetadata(
                distanceRange = atlas.get("distanceRange").asFloat,
                atlasSize = atlas.get("size").asFloat,
                atlasWidth = atlas.get("width").asFloat,
                atlasHeight = atlas.get("height").asFloat,
                lineHeight = metrics.get("lineHeight").asFloat,
                ascender = metrics.get("ascender").asFloat,
                descender = metrics.get("descender").asFloat,
                glyphs = glyphs,
            )
        }

        private fun boundsOf(o: JsonObject): Bounds = Bounds(
            left = o.get("left").asFloat,
            bottom = o.get("bottom").asFloat,
            right = o.get("right").asFloat,
            top = o.get("top").asFloat,
        )
    }
}
