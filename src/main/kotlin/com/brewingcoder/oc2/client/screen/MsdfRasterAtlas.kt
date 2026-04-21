package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-rasterizes the MSDF glyph atlas using CPU-side MSDF evaluation, producing a
 * high-resolution RGBA atlas compatible with the vanilla `position_tex_color` shader.
 *
 * The vanilla shader survives Iris/Oculus deferred pipelines without modification,
 * giving us ~3× sharper glyphs than the old 5×8 bitmap fallback while remaining
 * fully shader-pack-safe.
 *
 * Cell size: [CELL_W]×[CELL_H] pixels = 12×26. At JBMono's metrics
 * (lineHeight=1.32, ascender=1.02, advance=0.6) this gives ~19.7 px/em — enough
 * for smooth, readable text at typical monitor viewing distances.
 *
 * Generated once on first access (lazy, render-thread only). Registered as a
 * [DynamicTexture] in MC's texture manager; lives for the lifetime of the session.
 */
object MsdfRasterAtlas {

    /** Output cell width in pixels (= 0.6em × ~19.7px/em ≈ 11.8 → 12). */
    const val CELL_W = 12
    /** Output cell height in pixels (= lineHeight 1.32em × ~19.7px/em ≈ 26). */
    const val CELL_H = 26
    /** Grid columns — matches the simple `code % ATLAS_COLS` / `code / ATLAS_COLS` indexing. */
    const val ATLAS_COLS = 16

    val ATLAS_W: Float = (ATLAS_COLS * CELL_W).toFloat()   // 192
    val ATLAS_H: Float = (ATLAS_COLS * CELL_H).toFloat()   // 416

    @Volatile
    private var location: ResourceLocation? = null
    private var attempted = false

    /**
     * Returns the [ResourceLocation] for the rasterized atlas, generating it on
     * first call (one-time cost on the render thread, typically < 5 ms).
     * Returns null if generation failed or hasn't been attempted yet on a non-render thread.
     */
    fun getOrGenerate(): ResourceLocation? {
        if (location != null) return location
        if (attempted) return null
        if (!RenderSystem.isOnRenderThread()) return null
        attempted = true
        generate()
        return location
    }

    private fun generate() {
        try {
            val mc = Minecraft.getInstance()
            val rm = mc.resourceManager

            // Load MSDF atlas image
            val atlasRes = ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/font/jbmono_msdf.png")
            val atlasImg = rm.getResource(atlasRes).orElseThrow().open().use { NativeImage.read(it) }

            // Load glyph metadata
            val metaRes = ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "font_metadata/jbmono_msdf.json")
            val metaText = rm.getResource(metaRes).orElseThrow().openAsReader().use { it.readText() }
            val meta = parseMeta(metaText)

            // pixels per 1 em at target cell height (≈19.69)
            val pixPerEm = CELL_H.toFloat() / meta.lineHeight
            // distance range in output pixels
            val screenPxRange = meta.distanceRange * pixPerEm / meta.atlasEmSize

            val outW = (ATLAS_COLS * CELL_W)
            val outH = (ATLAS_COLS * CELL_H)
            val outImg = NativeImage(NativeImage.Format.RGBA, outW, outH, false)  // false = calloc (zero-initialized)

            for (code in 0x20..0xFF) {
                val g = meta.glyphs[code] ?: continue
                val pb = g.planeBounds ?: continue
                val ab = g.atlasBounds ?: continue

                val cellCol = code % ATLAS_COLS
                val cellRow = code / ATLAS_COLS
                val cellX0 = cellCol * CELL_W
                val cellY0 = cellRow * CELL_H

                for (py in 0 until CELL_H) {
                    // em Y above baseline (y=0 is cell top, going down)
                    val emY = meta.ascender - py.toFloat() / pixPerEm
                    // normalized position within glyph's atlas rect (0=bottom edge, 1=top edge)
                    val s = if (pb.top != pb.bottom) (emY - pb.bottom) / (pb.top - pb.bottom) else 0.5f
                    // atlas Y in NativeImage coords (NativeImage y=0 at top; MSDF yOrigin=bottom → invert)
                    val atlasY = atlasImg.height - (ab.bottom + s * (ab.top - ab.bottom))

                    for (px in 0 until CELL_W) {
                        // em X from cursor (x=0 is left edge of cell)
                        val emX = px.toFloat() / pixPerEm
                        // normalized position within glyph's atlas rect (0=left, 1=right)
                        val t = if (pb.right != pb.left) (emX - pb.left) / (pb.right - pb.left) else 0.5f
                        val atlasX = ab.left + t * (ab.right - ab.left)

                        val (r, g2, b) = bilinear(atlasImg, atlasX, atlasY)
                        // MSDF: 0.5 = on-edge; compute signed distance and smooth
                        val med = max(min(r - 0.5f, g2 - 0.5f), min(max(r - 0.5f, g2 - 0.5f), b - 0.5f))
                        val alpha = ((screenPxRange * med + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                        if (alpha > 0) {
                            // ABGR packing (NativeImage little-endian RGBA — R in byte 0, A in byte 3)
                            outImg.setPixelRGBA(cellX0 + px, cellY0 + py,
                                0x00FFFFFF or (alpha shl 24))
                        }
                    }
                }
            }

            val dynTex = DynamicTexture(outImg)
            dynTex.upload()
            location = mc.textureManager.register("oc2_msdf_raster", dynTex)
            atlasImg.close()
            OpenComputers2.LOGGER.info("MsdfRasterAtlas: generated ${outW}×${outH} atlas (${CELL_W}×${CELL_H} cells, screenPxRange=${"%.2f".format(screenPxRange)})")

        } catch (e: Exception) {
            OpenComputers2.LOGGER.error("MsdfRasterAtlas: generation failed — will use 5×8 bitmap fallback", e)
        }
    }

    /**
     * Bilinear sample of an MSDF NativeImage at fractional pixel coordinates.
     * NativeImage stores RGBA as ABGR int; R is the lowest byte.
     * Returns (r, g, b) in [0,1] — channels map to MSDF's three distance fields.
     */
    private fun bilinear(img: NativeImage, ax: Float, ay: Float): Triple<Float, Float, Float> {
        val x = (ax - 0.5f).let { if (it.isNaN() || it.isInfinite()) 0f else it }
        val y = (ay - 0.5f).let { if (it.isNaN() || it.isInfinite()) 0f else it }
        val x0 = x.toInt().coerceIn(0, img.width - 1)
        val x1 = (x0 + 1).coerceIn(0, img.width - 1)
        val y0 = y.toInt().coerceIn(0, img.height - 1)
        val y1 = (y0 + 1).coerceIn(0, img.height - 1)
        val fx = x - x.toInt().toFloat()
        val fy = y - y.toInt().toFloat()

        fun rgb(xi: Int, yi: Int): Triple<Float, Float, Float> {
            val c = img.getPixelRGBA(xi, yi)
            return Triple(
                (c and 0xFF) / 255f,
                ((c shr 8) and 0xFF) / 255f,
                ((c shr 16) and 0xFF) / 255f,
            )
        }

        val (r00, g00, b00) = rgb(x0, y0)
        val (r10, g10, b10) = rgb(x1, y0)
        val (r01, g01, b01) = rgb(x0, y1)
        val (r11, g11, b11) = rgb(x1, y1)
        fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        return Triple(
            mix(mix(r00, r10, fx), mix(r01, r11, fx), fy),
            mix(mix(g00, g10, fx), mix(g01, g11, fx), fy),
            mix(mix(b00, b10, fx), mix(b01, b11, fx), fy),
        )
    }

    // Minimal metadata needed for CPU rasterization
    private data class Bounds(val left: Float, val bottom: Float, val right: Float, val top: Float)
    private data class GlyphMeta(val planeBounds: Bounds?, val atlasBounds: Bounds?)
    private data class Meta(
        val distanceRange: Float,
        val atlasEmSize: Float,
        val lineHeight: Float,
        val ascender: Float,
        val glyphs: Map<Int, GlyphMeta>,
    )

    private fun parseMeta(text: String): Meta {
        val root = JsonParser.parseString(text).asJsonObject
        val atlas = root.getAsJsonObject("atlas")
        val metrics = root.getAsJsonObject("metrics")
        val glyphs = mutableMapOf<Int, GlyphMeta>()
        for (el in root.getAsJsonArray("glyphs")) {
            val o = el.asJsonObject
            val code = o.get("unicode").asInt
            fun bounds(key: String): Bounds? = o.getAsJsonObject(key)?.let {
                Bounds(it.get("left").asFloat, it.get("bottom").asFloat, it.get("right").asFloat, it.get("top").asFloat)
            }
            glyphs[code] = GlyphMeta(bounds("planeBounds"), bounds("atlasBounds"))
        }
        return Meta(
            distanceRange = atlas.get("distanceRange").asFloat,
            atlasEmSize = atlas.get("size").asFloat,
            lineHeight = metrics.get("lineHeight").asFloat,
            ascender = metrics.get("ascender").asFloat,
            glyphs = glyphs,
        )
    }
}
