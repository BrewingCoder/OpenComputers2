package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.block.MonitorBlock
import com.brewingcoder.oc2.block.MonitorBlockEntity
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import org.joml.Matrix4f

/**
 * CC:Tweaked-aligned monitor text rendering.
 *
 * Mechanism ported from `dan200.computercraft.client.render.text.FixedWidthFontRenderer`:
 *   - Font asset `oc2:textures/gui/term_font.png` — copied verbatim from CC:T (6×9 glyphs,
 *     16×16 grid, 2px padding, 256×256 atlas)
 *   - Render type `RenderType.text(FONT)` — vanilla `rendertype_text` shader, which is
 *     shader-pack-compatible by virtue of being a vanilla render type (Iris/Oculus only
 *     substitute shaders they know about)
 *   - Background fill via a 2×2 px solid patch at `(WIDTH-6, WIDTH-6)..(WIDTH-4, WIDTH-4)`
 *     in the font atlas — one quad per cell with the cell's bg color on the vertex
 *   - Foreground glyph via the character's cell in the atlas — one quad per non-space
 *     cell with the fg color on the vertex
 *   - Tiny Z offset between bg and fg to prevent z-fighting
 *   - Fog pushed to 1e4 and restored — same trick CC:T uses to stop deferred-composite
 *     fog from darkening far-monitor text to invisibility
 *
 * OC2-specific preserved:
 *   - HD pixel buffer (Pass 0, behind text) — user-facing API shipped in R2; not a text
 *     mechanism so keeping it is orthogonal to the text-rendering revert
 *   - Wall-mount-only facing (N/S/E/W), rotated into place before scaling
 *   - Group bounding box for frustum culling across the merged wall
 *
 * Data model difference from CC:T: our snapshot carries ARGB per cell rather than
 * palette-indexed colors, so the vertex-color setup is a direct int-decompose.
 */
class MonitorRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<MonitorBlockEntity> {

    // MSDF foreground renderer — used when no shader pack is active (Iris/Oculus break
    // custom shaders during deferred composite). pxPerEm = FONT_HEIGHT so 1 em maps to one
    // cell-height in our pixel-space coords; we step explicitly at FONT_WIDTH per cell so
    // the JBMono 0.6em advance doesn't drift relative to the bitmap's cell grid.
    private val msdfRenderer: MsdfTerminalRenderer by lazy { MsdfTerminalRenderer(FONT_HEIGHT.toFloat()) }

    private val diagLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private val diagLoggedEmit = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun render(
        be: MonitorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (!be.isMaster) return
        if (ShaderModCompat.isRenderingShadowPass()) return
        if (MonitorFrameCounter.shouldSkip(be)) return
        val snap = be.renderSnapshot() ?: return
        if (snap.rows.isEmpty()) return

        val facing = be.blockState.getValue(MonitorBlock.FACING)
        val groupW = be.groupBlocksWide
        val groupH = be.groupBlocksTall

        val savedFogStart = RenderSystem.getShaderFogStart()
        RenderSystem.setShaderFogStart(1e4f)

        poseStack.pushPose()

        val m = MARGIN_WORLD.toDouble()
        val (originDX, originDY, originDZ) = when (facing) {
            Direction.NORTH -> Triple(groupW.toDouble() - m, groupH.toDouble() - m, -Z_OFFSET.toDouble())
            Direction.SOUTH -> Triple(m, groupH.toDouble() - m, 1.0 + Z_OFFSET)
            Direction.EAST -> Triple(1.0 + Z_OFFSET, groupH.toDouble() - m, groupW.toDouble() - m)
            Direction.WEST -> Triple(-Z_OFFSET.toDouble(), groupH.toDouble() - m, m)
            else -> Triple(0.0, 0.0, 0.0)
        }
        poseStack.translate(originDX, originDY, originDZ)

        val yRotDeg = when (facing) {
            Direction.NORTH -> 180f
            Direction.SOUTH -> 0f
            Direction.EAST -> 90f
            Direction.WEST -> 270f
            else -> 0f
        }
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yRotDeg))

        // Scale pixel-space (pixelW × pixelH) to fit the drawable block-face area.
        // Separate X/Y scale factors so content always fills the monitor — mirrors CC:T's
        // MonitorBlockEntityRenderer.renderTerminal which uses independent xScale/yScale.
        // Z is scaled by 1f (NOT xScale) so Z_FG_OFFSET keeps its world-unit magnitude:
        // with xScale ≈ 0.008 on a 4-block monitor, scaling Z by xScale would compress
        // Z_FG_OFFSET (1e-4) down to ~8e-7 world units, well below 24-bit depth-buffer
        // precision at 10m distance — text fg + text bg would z-fight, producing the
        // viewing-angle-dependent speckle/diagonal artifacts.
        val cols = snap.cols
        val rowCount = snap.rows.size
        val pixelW = (cols * FONT_WIDTH).toFloat()
        val pixelH = (rowCount * FONT_HEIGHT).toFloat()
        val drawableW = groupW.toFloat() - 2f * MARGIN_WORLD
        val drawableH = groupH.toFloat() - 2f * MARGIN_WORLD
        val xScale = drawableW / pixelW
        val yScale = drawableH / pixelH
        poseStack.scale(xScale, -yScale, 1f)

        val matrix = poseStack.last().pose()

        // Pass 0 (OC2-only) — HD pixel buffer sampled as a single textured quad BEHIND the
        // text. Uses [MonitorPixelRenderType] which mirrors vanilla `RenderType.text(rl)`
        // (managed → Iris-compatible) but with depth-write disabled.
        //
        // Iris compat: under shader packs, `bufferSource` is an Iris BufferSourceWrapper
        // (MultiBufferSource, NOT a BufferSource subclass). `getBuffer()` works either way
        // since it's on the interface; `endBatch()` is BufferSource-only — call it only when
        // we have the real one. Under Iris, flush ordering is the wrapper's responsibility.
        val bs = bufferSource as? MultiBufferSource.BufferSource

        be.pixelSnapshot()?.let { (pxW, pxH, argb) ->
            val rl = MonitorPixelTextureCache.getOrUpload(be.blockPos, pxW, pxH, argb) ?: return@let
            val pixelRt = MonitorPixelRenderType.get(rl)
            val pxBuf = bufferSource.getBuffer(pixelRt)
            pxBuf.addVertex(matrix, 0f,     0f,     -Z_PIXEL_OFFSET).setColor(255, 255, 255, 255).setUv(0f, 0f).setLight(FULL_BRIGHT_LIGHTMAP)
            pxBuf.addVertex(matrix, 0f,     pixelH, -Z_PIXEL_OFFSET).setColor(255, 255, 255, 255).setUv(0f, 1f).setLight(FULL_BRIGHT_LIGHTMAP)
            pxBuf.addVertex(matrix, pixelW, pixelH, -Z_PIXEL_OFFSET).setColor(255, 255, 255, 255).setUv(1f, 1f).setLight(FULL_BRIGHT_LIGHTMAP)
            pxBuf.addVertex(matrix, pixelW, 0f,     -Z_PIXEL_OFFSET).setColor(255, 255, 255, 255).setUv(1f, 0f).setLight(FULL_BRIGHT_LIGHTMAP)
            bs?.endBatch(pixelRt)
        }

        // Pass 1: foreground glyphs ONLY. Text-cell backgrounds are intentionally skipped —
        // the HD pixel layer (Pass 0) IS the background. Drawing per-cell bg quads on top
        // of the pixel layer would either (a) cover pixel content with opaque cell-color
        // fills, or (b) z-fight with the pixel layer when both are translucent. Scripts
        // that want a colored backdrop should drawRect into the pixel buffer instead of
        // relying on setBackgroundColor.
        //
        // MSDF when no shader pack (crisp vector text). CC:T-bitmap fallback when a shader
        // pack is active OR while MSDF shader is still loading (first frames after world load).
        val shaderActive = ShaderModCompat.isShaderPackActive()
        val msdfShader = if (!shaderActive) MsdfShaders.get() else null
        if (msdfShader != null) {
            if (diagLogged.compareAndSet(false, true)) {
                com.brewingcoder.oc2.OpenComputers2.LOGGER.info(
                    "MonitorRenderer DIAG: MSDF branch, shaderActive=$shaderActive, snap.cols=$cols rows=$rowCount, bsIsBufferSource=${bs != null}"
                )
            }
            msdfShader.getUniform("ScreenPxRange")?.set(MSDF_SCREEN_PX_RANGE)
            poseStack.pushPose()
            poseStack.translate(0.0, 0.0, Z_FG_OFFSET.toDouble())
            val fgMatrix = poseStack.last().pose()
            val msdfBuf = bufferSource.getBuffer(MsdfShaders.MSDF_TEXT)
            val emitted = drawForegroundsMsdf(msdfBuf, fgMatrix, snap, cols, rowCount)
            bs?.endBatch(MsdfShaders.MSDF_TEXT)
            poseStack.popPose()
            if (emitted > 0 && diagLogged.get() && !diagLoggedEmit.getAndSet(true)) {
                com.brewingcoder.oc2.OpenComputers2.LOGGER.info("MonitorRenderer DIAG: MSDF emitted $emitted glyphs first frame")
            }
        } else {
            if (diagLogged.compareAndSet(false, true)) {
                com.brewingcoder.oc2.OpenComputers2.LOGGER.info(
                    "MonitorRenderer DIAG: BITMAP branch, shaderActive=$shaderActive, msdfShaderNull=${MsdfShaders.get() == null}"
                )
            }
            val buf = bufferSource.getBuffer(TERMINAL)
            drawForegrounds(buf, matrix, snap, cols, rowCount)
            bs?.endBatch(TERMINAL)
        }

        poseStack.popPose()
        RenderSystem.setShaderFogStart(savedFogStart)
    }

    private fun drawBackgrounds(
        buf: VertexConsumer, matrix: Matrix4f,
        snap: MonitorBlockEntity.RenderSnapshot, cols: Int, rowCount: Int,
    ) {
        for (rowIdx in 0 until rowCount) {
            val y0 = (rowIdx * FONT_HEIGHT).toFloat()
            val y1 = y0 + FONT_HEIGHT
            for (col in 0 until cols) {
                val color = snap.bg[rowIdx * cols + col]
                if ((color ushr 24) and 0xFF == 0) continue
                val x0 = (col * FONT_WIDTH).toFloat()
                val x1 = x0 + FONT_WIDTH
                quad(buf, matrix, x0, y0, x1, y1, 0f, color,
                    BACKGROUND_START, BACKGROUND_START, BACKGROUND_END, BACKGROUND_END)
            }
        }
    }

    private fun drawForegrounds(
        buf: VertexConsumer, matrix: Matrix4f,
        snap: MonitorBlockEntity.RenderSnapshot, cols: Int, rowCount: Int,
    ) {
        for (rowIdx in 0 until rowCount) {
            val line = snap.rows[rowIdx]
            val y0 = (rowIdx * FONT_HEIGHT).toFloat()
            val y1 = y0 + FONT_HEIGHT
            val lineLen = minOf(line.length, cols)
            for (col in 0 until lineLen) {
                val ch = line[col]
                if (ch == ' ' || ch == ' ') continue
                val color = snap.fg[rowIdx * cols + col]
                if ((color ushr 24) and 0xFF == 0) continue
                var code = ch.code
                if (code > 255) code = '?'.code
                val column = code % 16
                val row = code / 16
                val xStart = 1 + column * (FONT_WIDTH + 2)
                val yStart = 1 + row * (FONT_HEIGHT + 2)
                val x0 = (col * FONT_WIDTH).toFloat()
                val x1 = x0 + FONT_WIDTH
                quad(buf, matrix, x0, y0, x1, y1, Z_FG_OFFSET, color,
                    xStart / WIDTH, yStart / WIDTH,
                    (xStart + FONT_WIDTH) / WIDTH, (yStart + FONT_HEIGHT) / WIDTH)
            }
        }
    }

    /**
     * MSDF foreground emitter — same per-cell loop as [drawForegrounds] but routes glyphs
     * through [msdfRenderer] for vector-quality output. Steps explicitly at FONT_WIDTH per
     * cell (ignoring JBMono's 0.6em advance) so cell positions stay grid-aligned with the
     * bitmap path's coordinate system.
     */
    private fun drawForegroundsMsdf(
        buf: VertexConsumer, matrix: Matrix4f,
        snap: MonitorBlockEntity.RenderSnapshot, cols: Int, rowCount: Int,
    ): Int {
        val units = FONT_HEIGHT.toFloat()
        var emitted = 0
        for (rowIdx in 0 until rowCount) {
            val line = snap.rows[rowIdx]
            val y0 = (rowIdx * FONT_HEIGHT).toFloat()
            val lineLen = minOf(line.length, cols)
            for (col in 0 until lineLen) {
                val ch = line[col]
                if (ch == ' ' || ch == ' ') continue
                val color = snap.fg[rowIdx * cols + col]
                if ((color ushr 24) and 0xFF == 0) continue
                val x0 = (col * FONT_WIDTH).toFloat()
                msdfRenderer.drawLineToBuffer(buf, matrix, x0, y0, ch.toString(), color, units)
                emitted++
            }
        }
        return emitted
    }

    /** Single-quad emitter. Vertex order matches CC:T: TL, BL, BR, TR. */
    private fun quad(
        buf: VertexConsumer, matrix: Matrix4f,
        x1: Float, y1: Float, x2: Float, y2: Float, z: Float, argb: Int,
        u1: Float, v1: Float, u2: Float, v2: Float,
    ) {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        buf.addVertex(matrix, x1, y1, z).setColor(r, g, b, a).setUv(u1, v1).setLight(FULL_BRIGHT_LIGHTMAP)
        buf.addVertex(matrix, x1, y2, z).setColor(r, g, b, a).setUv(u1, v2).setLight(FULL_BRIGHT_LIGHTMAP)
        buf.addVertex(matrix, x2, y2, z).setColor(r, g, b, a).setUv(u2, v2).setLight(FULL_BRIGHT_LIGHTMAP)
        buf.addVertex(matrix, x2, y1, z).setColor(r, g, b, a).setUv(u2, v1).setLight(FULL_BRIGHT_LIGHTMAP)
    }

    override fun shouldRenderOffScreen(be: MonitorBlockEntity): Boolean =
        be.isMaster && (be.groupBlocksWide > 1 || be.groupBlocksTall > 1)

    override fun getViewDistance(): Int = 64

    override fun getRenderBoundingBox(be: MonitorBlockEntity): AABB {
        if (!be.isMaster) return super.getRenderBoundingBox(be)
        val pos = be.blockPos
        val w = be.groupBlocksWide.toDouble()
        val h = be.groupBlocksTall.toDouble()
        val facing = be.blockState.getValue(MonitorBlock.FACING)
        return when (facing) {
            Direction.NORTH, Direction.SOUTH ->
                AABB(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                     pos.x + w, pos.y + h, pos.z + 1.0)
            Direction.EAST, Direction.WEST ->
                AABB(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                     pos.x + 1.0, pos.y + h, pos.z + w)
            else -> super.getRenderBoundingBox(be)
        }
    }

    companion object {
        val FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath("oc2", "textures/gui/term_font.png")
        val TERMINAL: RenderType = RenderType.text(FONT)

        const val FONT_WIDTH = 6
        const val FONT_HEIGHT = 9
        const val WIDTH = 256.0f
        val BACKGROUND_START = (WIDTH - 6.0f) / WIDTH
        val BACKGROUND_END = (WIDTH - 4.0f) / WIDTH
        const val FULL_BRIGHT_LIGHTMAP = (0xF shl 4) or (0xF shl 20)

        // Block-face clearance in world units. Bumped from 0.001 → 0.005 because at view
        // distances >~30m, 24-bit depth buffer precision exceeds 1mm and the pixel layer
        // started z-fighting with the underlying block model. 5mm gives ~5x precision
        // headroom while staying invisibly close to the face.
        private const val Z_OFFSET = 0.005f
        // Pixel layer pose-z. Clearly behind text bg so they don't tie at the rasterizer.
        // Pixel doesn't write depth, so this only affects fragment ordering with text bg
        // (not the block-face occlusion test, which still uses Z_OFFSET clearance).
        private const val Z_PIXEL_OFFSET = 1e-3f
        private const val Z_FG_OFFSET = 1e-4f
        private const val MARGIN_WORLD = 0.04f

        // Fixed value chosen for typical viewing distances. Real value varies with camera
        // distance + GUI scale; refactor later to compute per-frame if edges look soft at
        // close range or aliased at far range.
        private const val MSDF_SCREEN_PX_RANGE = 6f
    }
}
