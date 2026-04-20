package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.block.MonitorBlock
import com.brewingcoder.oc2.block.MonitorBlockEntity
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB

/**
 * Draws the monitor's text buffer onto the front face of the block group.
 *
 * Only the master BE renders — the master spans the full WxH-block surface in
 * one draw call by emitting quads that extend beyond its own block bounds.
 * Slaves are passive: their face texture shows through the underlying block
 * model, and the master's MSDF text quad covers it.
 *
 * Geometry:
 *   - Block face is at z = (some pos within the block, slightly offset from
 *     the model surface to avoid z-fighting) in pre-rotation coords
 *   - Origin at the master block's center, then translated to the screen's
 *     top-left corner of the group
 *   - Rotation aligns the rendering plane with the block facing direction
 */
class MonitorRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<MonitorBlockEntity> {

    /** Lightweight reusable text-emitter sized for world-space rendering. */
    private val textRenderer = MsdfTerminalRenderer(pxPerEm = 1f)

    override fun render(
        be: MonitorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (!be.isMaster) return  // slaves render nothing
        // Iris/Oculus call render() multiple times per visible frame (gbuffer,
        // shadow, deferred composites). Skip the shadow pass entirely so our
        // text doesn't end up baked into the shadow map. Same trick CC:Tweaked
        // uses — see [ShaderModCompat].
        if (ShaderModCompat.isRenderingShadowPass()) return
        // Dedup non-shadow passes too — multiple gbuffer-stage calls within one
        // visible frame would otherwise re-render and (with our MSDF custom
        // shader) potentially clobber prior writes.
        if (MonitorFrameCounter.shouldSkip(be)) return
        val snap = be.renderSnapshot() ?: return
        if (snap.rows.isEmpty()) return

        val facing = be.blockState.getValue(MonitorBlock.FACING)
        val groupW = be.groupBlocksWide
        val groupH = be.groupBlocksTall

        // Push fog out to extreme distance for our draw — Iris/Oculus deferred
        // composites otherwise apply fog that darkens our text to invisibility.
        // Same trick CC:Tweaked uses. Restore at end.
        val savedFogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart()
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(1e4f)

        poseStack.pushPose()

        // Player view derivation per facing (MC coords: +X east, +Y up, +Z south):
        //
        // | Facing | Front face | Player at | Player looks | Player's RIGHT in world |
        // |--------|-----------|-----------|--------------|-------------------------|
        // | NORTH  | -Z side   | -Z (N)    | +Z (S)       | -X (W)                  |
        // | SOUTH  | +Z side   | +Z (S)    | -Z (N)       | +X (E)                  |
        // | EAST   | +X side   | +X (E)    | -X (W)       | -Z (N)                  |
        // | WEST   | -X side   | -X (W)    | +X (E)       | +Z (S)                  |
        //
        // Master invariant (from MonitorMerge): for N/S facings master = smallest (y, x);
        // for E/W facings master = smallest (y, z). Master is at world-min-corner of the group.
        //
        // Strategy: translate to the group's TOP-LEFT-IN-PLAYER-VIEW corner, just outside
        // the front face. Then Y-rotate so local +X aligns with player's right.
        // Top-LEFT in player view, inset by MARGIN_WORLD so text doesn't run up
        // against the bezel. Inset shifts INWARD: in player's right direction (away
        // from left edge) and player's down direction (away from top edge).
        val m = MARGIN_WORLD.toDouble()
        val (originDX, originDY, originDZ) = when (facing) {
            // NORTH: top-LEFT-PLAYER = (max world X, max world Y). Inward right = -X.
            Direction.NORTH -> Triple(groupW.toDouble() - m, groupH.toDouble() - m, -Z_OFFSET.toDouble())
            // SOUTH: top-LEFT-PLAYER = (min world X, max world Y). Inward right = +X.
            Direction.SOUTH -> Triple(m, groupH.toDouble() - m, 1.0 + Z_OFFSET)
            // EAST: top-LEFT-PLAYER = (max world Z, max world Y). Inward right = -Z.
            Direction.EAST -> Triple(1.0 + Z_OFFSET, groupH.toDouble() - m, groupW.toDouble() - m)
            // WEST: top-LEFT-PLAYER = (min world Z, max world Y). Inward right = +Z.
            Direction.WEST -> Triple(-Z_OFFSET.toDouble(), groupH.toDouble() - m, m)
            else -> Triple(0.0, 0.0, 0.0)
        }
        poseStack.translate(originDX, originDY, originDZ)

        // Rotate so local +X = player's RIGHT in world coords.
        // YP CCW looking from +Y down: 90° maps local (1,0,0) → world (0,0,-1) (= -Z).
        val yRotDeg = when (facing) {
            Direction.NORTH -> 180f    // local +X → world -X
            Direction.SOUTH -> 0f      // local +X → world +X
            Direction.EAST -> 90f      // local +X → world -Z
            Direction.WEST -> 270f     // local +X → world +Z
            else -> 0f
        }
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yRotDeg))

        // Scale: text must fill the drawable area (group size minus 2× margin per axis).
        // Calibrate to width so all CHAR_ADVANCE_EM × COLS_PER_BLOCK × groupW chars fit.
        // Same scale applied to Y (sign-flipped) — gives consistent glyph aspect ratio.
        // Char width thus varies slightly with group size (4-5% range across 1×1 to ∞×∞);
        // tradeoff for visual consistency across facings.
        val drawableW = groupW.toFloat() - 2f * MARGIN_WORLD
        val emToWorld = drawableW / (CHAR_ADVANCE_EM * MonitorBlockEntity.COLS_PER_BLOCK * groupW)
        poseStack.scale(emToWorld, -emToWorld, emToWorld)

        val matrix = poseStack.last().pose()
        val rowHeight = textRenderer.fontLineHeight  // em units
        val cellWidth = CHAR_ADVANCE_EM           // em units; matches JBMono advance
        val surfaceW = cellWidth * snap.cols
        val surfaceH = rowHeight * snap.rows.size

        // ---- Pass 0: HD pixel buffer (under everything) ----
        // Sample the per-master DynamicTexture as a single textured quad
        // covering the full drawable surface. Vanilla position_tex_color shader
        // → shader-pack-safe. Depth test off so layering with bg/text (deferred,
        // same-z) works regardless of draw order.
        be.pixelSnapshot()?.let { (pxW, pxH, argb) ->
            val texId = MonitorPixelTextureCache.getOrUpload(be.blockPos, pxW, pxH, argb)
            if (texId != 0) {
                RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
                RenderSystem.setShaderTexture(0, texId)
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                RenderSystem.disableCull()
                RenderSystem.disableDepthTest()
                val builder = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
                builder.addVertex(matrix, 0f,        surfaceH, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255)
                builder.addVertex(matrix, surfaceW,  surfaceH, 0f).setUv(1f, 1f).setColor(255, 255, 255, 255)
                builder.addVertex(matrix, surfaceW,  0f,       0f).setUv(1f, 0f).setColor(255, 255, 255, 255)
                builder.addVertex(matrix, 0f,        0f,       0f).setUv(0f, 0f).setColor(255, 255, 255, 255)
                BufferUploader.drawWithShader(builder.buildOrThrow())
                RenderSystem.enableDepthTest()
                RenderSystem.enableCull()
            }
        }

        // ---- Pass 1: per-cell background fills (only non-transparent cells) ----
        // Drawn FIRST so the text overlays correctly and bg colors don't punch holes
        // through the glyphs.
        val bgBuffer = bufferSource.getBuffer(MsdfShaders.MONITOR_BG_FILL)
        for (rowIdx in snap.rows.indices) {
            val y0 = rowIdx * rowHeight
            val y1 = y0 + rowHeight
            for (col in 0 until snap.cols) {
                val color = snap.bg[rowIdx * snap.cols + col]
                if ((color ushr 24) and 0xFF == 0) continue  // fully transparent — skip
                val x0 = col * cellWidth
                val x1 = x0 + cellWidth
                val a = ((color ushr 24) and 0xFF)
                val r = ((color ushr 16) and 0xFF)
                val g = ((color ushr 8) and 0xFF)
                val b = (color and 0xFF)
                bgBuffer.addVertex(matrix, x0, y1, 0f).setColor(r, g, b, a)
                bgBuffer.addVertex(matrix, x1, y1, 0f).setColor(r, g, b, a)
                bgBuffer.addVertex(matrix, x1, y0, 0f).setColor(r, g, b, a)
                bgBuffer.addVertex(matrix, x0, y0, 0f).setColor(r, g, b, a)
            }
        }

        // Force the bg pass to flush BEFORE text. Both submit translucent quads
        // at z=0 to the same MultiBufferSource — without explicit end-batch, the
        // dispatcher chooses an order, and on the bitmap path text was being
        // drawn UNDER bg fills (opaque cell bgs occluded the glyphs).
        (bufferSource as? net.minecraft.client.renderer.MultiBufferSource.BufferSource)
            ?.endBatch(MsdfShaders.MONITOR_BG_FILL)

        // ---- Pass 2: glyph quads ----
        // MSDF custom shader is the high-quality path but Iris/Oculus substitute
        // mod custom shaders during deferred composite, producing invisible
        // output. Fall back to bitmap atlas + vanilla position_tex_color shader
        // when a shader pack is loaded.
        // Per-frame check — Iris's shader-toggle hotkey flips this without any
        // world reload, so caching the answer at startup would leave us in the
        // wrong path until next launch.
        if (ShaderModCompat.isShaderPackActive()) {
            renderTextBitmap(bufferSource, matrix, snap, rowHeight, cellWidth)
        } else {
            renderTextMsdf(bufferSource, matrix, snap, rowHeight, cellWidth)
        }

        poseStack.popPose()
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(savedFogStart)
    }

    /** Bitmap atlas glyph quads via vanilla `position_tex_color` shader (shader-pack-safe). */
    private fun renderTextBitmap(
        bufferSource: MultiBufferSource,
        matrix: org.joml.Matrix4f,
        snap: MonitorBlockEntity.RenderSnapshot,
        rowHeight: Float,
        cellWidth: Float,
    ) {
        val buf = bufferSource.getBuffer(MsdfShaders.MONITOR_TEXT_BITMAP)
        val atlasW = MsdfShaders.BITMAP_ATLAS_W
        val atlasH = MsdfShaders.BITMAP_ATLAS_H
        val atlasCols = MsdfShaders.BITMAP_ATLAS_COLS
        val cellPxW = MsdfShaders.BITMAP_CELL_PX_W
        val cellPxH = MsdfShaders.BITMAP_CELL_PX_H
        for (rowIdx in snap.rows.indices) {
            val line = snap.rows[rowIdx]
            val y0 = rowIdx * rowHeight
            val y1 = y0 + rowHeight
            for (col in line.indices) {
                val ch = line[col]
                if (ch == ' ') continue
                val code = ch.code
                if (code !in 0x20..0xFF) continue
                val srcCol = code % atlasCols
                val srcRow = code / atlasCols
                val u0 = srcCol * cellPxW / atlasW
                val u1 = (srcCol + 1) * cellPxW / atlasW
                val v0 = srcRow * cellPxH / atlasH
                val v1 = (srcRow + 1) * cellPxH / atlasH
                val x0 = col * cellWidth
                val x1 = x0 + cellWidth
                val color = snap.fg[rowIdx * snap.cols + col]
                val a = ((color ushr 24) and 0xFF)
                val r = ((color ushr 16) and 0xFF)
                val g = ((color ushr 8) and 0xFF)
                val b = (color and 0xFF)
                buf.addVertex(matrix, x0, y1, 0f).setUv(u0, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, x1, y1, 0f).setUv(u1, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, x1, y0, 0f).setUv(u1, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, x0, y0, 0f).setUv(u0, v0).setColor(r, g, b, a)
            }
        }
    }

    /** MSDF vector glyphs via custom shader — better quality, no-shader-pack only. */
    private fun renderTextMsdf(
        bufferSource: MultiBufferSource,
        matrix: org.joml.Matrix4f,
        snap: MonitorBlockEntity.RenderSnapshot,
        rowHeight: Float,
        cellWidth: Float,
    ) {
        val shader = MsdfShaders.get() ?: return
        shader.getUniform("ScreenPxRange")?.set(WORLD_SCREEN_PX_RANGE)
        val buf = bufferSource.getBuffer(MsdfShaders.MSDF_TEXT)
        for (rowIdx in snap.rows.indices) {
            val line = snap.rows[rowIdx]
            val y = rowIdx * rowHeight
            for (col in line.indices) {
                val ch = line[col]
                if (ch == ' ') continue
                val color = snap.fg[rowIdx * snap.cols + col]
                val x = col * cellWidth
                textRenderer.drawLineToBuffer(buf, matrix, x, y, ch.toString(), color, 1f)
            }
        }
    }

    /** Render the master's full multi-block surface even when only the master is in view. */
    override fun shouldRenderOffScreen(be: MonitorBlockEntity): Boolean = be.isMaster && (be.groupBlocksWide > 1 || be.groupBlocksTall > 1)

    /** AABB needs to span the whole group, not just the master block. */
    override fun getViewDistance(): Int = 64

    /**
     * Override the BE's render bounding box to cover the FULL group, not just
     * the master block. Without this, MC's frustum culling drops the BE when
     * the master block is off-screen but the group's text is still on-screen
     * (visible when the player gets close to the wall and looks at it from an
     * acute angle — the master might be outside the camera frustum even though
     * other group blocks aren't).
     *
     * Slaves return their own block AABB (they don't render anything anyway).
     */
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
        private const val Z_OFFSET = 0.001f          // tiny push so text floats just above the face
        private const val CHAR_ADVANCE_EM = 0.6f     // JBMono Regular's monospace advance
        private const val WORLD_SCREEN_PX_RANGE = 8f // empirically clean across normal viewing distances
        private const val MARGIN_WORLD = 0.04f       // ~4cm bezel; ~4% of a block face
    }
}
