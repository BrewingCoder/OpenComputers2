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

        // Skip the entire render if the camera is behind the front face plane.
        // All three passes call disableCull() (required because the Y-flip in the
        // pose-stack scale inverts winding order), so without this gate the text,
        // pixel layer, and cell backgrounds would render visibly through the
        // back of the block. Cheap half-space test — same pattern CC:T uses.
        val camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera.position
        val bp = be.blockPos
        val onFront = when (facing) {
            Direction.NORTH -> camera.z < bp.z + 0.5   // front face normal = -Z
            Direction.SOUTH -> camera.z > bp.z + 0.5   // front face normal = +Z
            Direction.EAST  -> camera.x > bp.x + 0.5   // front face normal = +X
            Direction.WEST  -> camera.x < bp.x + 0.5   // front face normal = -X
            else -> true
        }
        if (!onFront) return

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

        // ---- Pass 0: per-cell background fills (only non-transparent cells) ----
        // IMMEDIATE MODE. Under Iris's FullyBufferedMultiBufferSource (DW20),
        // buffered submissions are deferred to a late composite stage and the
        // endBatch(MONITOR_BG_FILL) hint is a no-op — causing bg fills to
        // render AFTER immediate-mode text (covering the glyphs). Drawing bg
        // immediate-mode, before text, puts ordering under our direct control.
        run {
            com.mojang.blaze3d.systems.RenderSystem.setShader {
                net.minecraft.client.renderer.GameRenderer.getPositionColorShader()
            }
            com.mojang.blaze3d.systems.RenderSystem.enableBlend()
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
            com.mojang.blaze3d.systems.RenderSystem.disableCull()
            // Explicitly enable depth TEST (world geometry occludes our content)
            // and disable depth WRITE (our internal passes still layer by draw
            // order — the shipped contract). depthMask alone is not enough;
            // if some upstream pass left depth-test disabled, our content would
            // bleed through any block placed in front of the face.
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest()
            com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false)
            val bgBuilder = com.mojang.blaze3d.vertex.Tesselator.getInstance()
                .begin(
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                )
            var bgQuads = 0
            for (rowIdx in snap.rows.indices) {
                val y0 = rowIdx * rowHeight
                val y1 = y0 + rowHeight
                for (col in 0 until snap.cols) {
                    val color = snap.bg[rowIdx * snap.cols + col]
                    if ((color ushr 24) and 0xFF == 0) continue  // transparent cells skipped
                    val x0 = col * cellWidth
                    val x1 = x0 + cellWidth
                    val a = ((color ushr 24) and 0xFF)
                    val r = ((color ushr 16) and 0xFF)
                    val g = ((color ushr 8) and 0xFF)
                    val b = (color and 0xFF)
                    bgBuilder.addVertex(matrix, x0, y1, 0f).setColor(r, g, b, a)
                    bgBuilder.addVertex(matrix, x1, y1, 0f).setColor(r, g, b, a)
                    bgBuilder.addVertex(matrix, x1, y0, 0f).setColor(r, g, b, a)
                    bgBuilder.addVertex(matrix, x0, y0, 0f).setColor(r, g, b, a)
                    bgQuads++
                }
            }
            val bgMesh = bgBuilder.build()
            if (bgMesh != null) com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bgMesh)
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true)
            com.mojang.blaze3d.systems.RenderSystem.enableCull()
            if (com.brewingcoder.oc2.client.screen.MonitorFrameCounter.current() % 60 == 0L) {
                com.brewingcoder.oc2.OpenComputers2.LOGGER.info(
                    "MonitorRenderer.DIAG.bg.IMMEDIATE: quads=$bgQuads meshNull=${bgMesh == null}"
                )
            }
        }

        // ---- Pass 1: HD pixel buffer (over cell bg, under glyphs) ----
        // Drawn AFTER cell bg so bars/shapes painted by scripts visually overlay
        // the terminal background — text strokes then float on top via MSDF alpha,
        // letting the bar show through the gaps between glyph parts.
        be.pixelSnapshot()?.let { (pxW, pxH, argb) ->
            val texLoc = MonitorPixelTextureCache.getOrUpload(be.blockPos, pxW, pxH, argb)
            if (texLoc != null) {
                RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
                RenderSystem.setShaderTexture(0, texLoc)
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
                RenderSystem.disableCull()
                RenderSystem.enableDepthTest()
                RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
                RenderSystem.depthMask(false)
                val builder = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
                builder.addVertex(matrix, 0f,        surfaceH, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255)
                builder.addVertex(matrix, surfaceW,  surfaceH, 0f).setUv(1f, 1f).setColor(255, 255, 255, 255)
                builder.addVertex(matrix, surfaceW,  0f,       0f).setUv(1f, 0f).setColor(255, 255, 255, 255)
                builder.addVertex(matrix, 0f,        0f,       0f).setUv(0f, 0f).setColor(255, 255, 255, 255)
                BufferUploader.drawWithShader(builder.buildOrThrow())
                RenderSystem.depthMask(true)
                RenderSystem.enableCull()
            }
        }

        // ---- Pass 2: glyph quads ----
        // MSDF custom shader is the high-quality path but Iris/Oculus substitute
        // mod custom shaders during deferred composite, producing invisible
        // output. Fall back to bitmap atlas + vanilla position_tex_color shader
        // when a shader pack is loaded.
        // Per-frame check — Iris's shader-toggle hotkey flips this without any
        // world reload, so caching the answer at startup would leave us in the
        // wrong path until next launch.
        val shaderPackActive = ShaderModCompat.isShaderPackActive()
        // DIAG 2026-04-22: log every 60 frames which path we're on
        if (com.brewingcoder.oc2.client.screen.MonitorFrameCounter.current() % 60 == 0L) {
            var nonBlankCells = 0
            var firstNonBlankRow = -1
            var sampleText = ""
            for ((i, row) in snap.rows.withIndex()) {
                for (c in row) if (c != ' ') { nonBlankCells++ }
                if (firstNonBlankRow == -1 && row.any { it != ' ' }) {
                    firstNonBlankRow = i
                    sampleText = row.trim().take(60)
                }
            }
            com.brewingcoder.oc2.OpenComputers2.LOGGER.info(
                "MonitorRenderer.DIAG: shaderPack=$shaderPackActive rows=${snap.rows.size} cols=${snap.cols} nonBlankCells=$nonBlankCells firstTextRow=$firstNonBlankRow sample='$sampleText'"
            )
        }
        // DIAG 2026-04-22: force bitmap path unconditionally (Iris may be
        // substituting our MSDF shader even with no pack active).
        renderTextBitmap(bufferSource, matrix, snap, rowHeight, cellWidth)

        // ---- Pass 3: icon overlay (above text, above pixels) ----
        // Unified pass — items route through itemRenderer, fluids draw as
        // textured quads off the block atlas + client-side tint color,
        // chemicals draw as textured quads off Mekanism's chemical atlas
        // via the soft-dep bridge.
        val icons = be.iconSnapshot()
        if (icons.isNotEmpty()) {
            val iconPxW = snap.cols * MonitorBlockEntity.PX_PER_CELL
            val iconPxH = snap.rows.size * MonitorBlockEntity.PX_PER_CELL
            val fluidIcons = icons.filter { it.kind == "fluid" }
            if (fluidIcons.isNotEmpty()) {
                renderFluidIcons(poseStack, fluidIcons, iconPxW, iconPxH, surfaceW, surfaceH)
            }
            val chemicalIcons = icons.filter { it.kind == "chemical" }
            if (chemicalIcons.isNotEmpty()) {
                renderChemicalIcons(poseStack, chemicalIcons, iconPxW, iconPxH, surfaceW, surfaceH)
            }
            val itemIcons = icons.filter { it.kind != "fluid" && it.kind != "chemical" }
            if (itemIcons.isNotEmpty()) {
                renderItemIcons(poseStack, bufferSource, packedLight, packedOverlay,
                    itemIcons, iconPxW, iconPxH, surfaceW, surfaceH)
            }
        }

        poseStack.popPose()
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(savedFogStart)
    }

    /**
     * Draws every pending [MonitorBlockEntity.IconOverlay] as a standard MC item
     * sprite using `ItemDisplayContext.GUI` (flat inventory-style).
     *
     * Icons live ABOVE the text and HD-pixel layers — they occlude anything
     * below them in the z-order but are themselves subject to whatever comes
     * afterward in the render stack (nothing in v1).
     *
     * Aspect note: ([x], [y], [sizePx]) coordinates are in the master's pixel
     * buffer grid (which is NOT square — see [MonitorBlockEntity.PX_PER_CELL]
     * vs character advance). Callers who want a visually-square icon should
     * reason about `MonitorPeripheral.getPixelSize()` and cell aspect themselves.
     * ui_v1's `Icon(shape="item")` widget handles this for scripts.
     */
    private fun renderItemIcons(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
        icons: List<MonitorBlockEntity.IconOverlay>,
        pxW: Int,
        pxH: Int,
        surfaceW: Float,
        surfaceH: Float,
    ) {
        if (pxW <= 0 || pxH <= 0) return
        val mc = net.minecraft.client.Minecraft.getInstance()
        val level = mc.level
        for (icon in icons) {
            val rl = net.minecraft.resources.ResourceLocation.tryParse(icon.itemId) ?: continue
            val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl)
            if (item == net.minecraft.world.item.Items.AIR) continue  // unknown id → AIR fallback
            val stack = net.minecraft.world.item.ItemStack(item)
            val emX = (icon.x.toFloat() / pxW) * surfaceW
            val emY = (icon.y.toFloat() / pxH) * surfaceH
            val emW = (icon.wPx.toFloat() / pxW) * surfaceW
            val emH = (icon.hPx.toFloat() / pxH) * surfaceH
            poseStack.pushPose()
            // Translate to icon CENTER; GUI context renders items around their origin
            // in a [-0.5, 0.5] box. Push slightly forward so the item sits ABOVE the
            // text quads rather than z-fighting.
            poseStack.translate(emX + emW * 0.5f, emY + emH * 0.5f, ICON_Z_LIFT)
            // Outer pose has -Y scale (screen-down = +Y local). Items use +Y = up,
            // so flip Y back here. Z kept uniform with the larger of W/H so 3D
            // blocks don't look squished when pxW ≠ pxH mapping is asymmetric.
            val depthScale = kotlin.math.max(emW, emH)
            poseStack.scale(emW, -emH, depthScale)
            mc.itemRenderer.renderStatic(
                stack,
                net.minecraft.world.item.ItemDisplayContext.GUI,
                packedLight,
                packedOverlay,
                poseStack,
                bufferSource,
                level,
                0,
            )
            poseStack.popPose()
        }
        // Flush under vanilla BufferSource; no-op under deferred pipelines (Iris
        // handles its own item batching).
        (bufferSource as? net.minecraft.client.renderer.MultiBufferSource.BufferSource)?.endBatch()
    }

    /**
     * Draws fluid-kind [MonitorBlockEntity.IconOverlay]s as tinted textured quads
     * off the block atlas. Fluids don't have ItemStacks (gases, unbucketable fluids,
     * Mekanism chemicals…) so we bypass [net.minecraft.client.renderer.entity.ItemRenderer]
     * and sample the still-frame sprite directly from the block atlas, applying the
     * fluid type's client tint color.
     *
     * Immediate mode via `position_tex_color` — same pattern as the text path
     * ([renderTextBitmap]). Plays nicely with Iris's FullyBufferedMultiBufferSource
     * since we don't go through any RenderType.
     */
    private fun renderFluidIcons(
        poseStack: PoseStack,
        icons: List<MonitorBlockEntity.IconOverlay>,
        pxW: Int,
        pxH: Int,
        surfaceW: Float,
        surfaceH: Float,
    ) {
        if (pxW <= 0 || pxH <= 0) return
        val mc = net.minecraft.client.Minecraft.getInstance()
        val atlas = mc.modelManager.getAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
        val matrix = poseStack.last().pose()

        RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
        RenderSystem.setShaderTexture(0, net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
        RenderSystem.depthMask(false)

        val builder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        var quadsEmitted = 0

        for (icon in icons) {
            val rl = net.minecraft.resources.ResourceLocation.tryParse(icon.id) ?: continue
            val fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.get(rl)
            if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) continue
            val ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid)
            val fluidStack = net.neoforged.neoforge.fluids.FluidStack(fluid, 1000)
            val stillLoc = ext.getStillTexture(fluidStack) ?: continue
            val sprite = atlas.getSprite(stillLoc)
            val tint = ext.getTintColor(fluidStack)

            val emX = (icon.x.toFloat() / pxW) * surfaceW
            val emY = (icon.y.toFloat() / pxH) * surfaceH
            val emW = (icon.wPx.toFloat() / pxW) * surfaceW
            val emH = (icon.hPx.toFloat() / pxH) * surfaceH

            val u0 = sprite.u0; val u1 = sprite.u1
            val v0 = sprite.v0; val v1 = sprite.v1

            // Many fluid tints have alpha=0 (water is 0xFF3F76E5 but lava is sometimes 0x00FFFFFF).
            // Treat alpha=0 as "fully opaque" — it means "no tint override", not transparent.
            val ta = (tint ushr 24) and 0xFF
            val r = (tint ushr 16) and 0xFF
            val g = (tint ushr 8) and 0xFF
            val b = tint and 0xFF
            val a = if (ta == 0) 255 else ta

            val z = ICON_Z_LIFT
            // Match the item-icon CCW winding (bottom-left → bottom-right → top-right → top-left)
            // so both layers are visible from the same face direction.
            builder.addVertex(matrix, emX,        emY + emH, z).setUv(u0, v1).setColor(r, g, b, a)
            builder.addVertex(matrix, emX + emW,  emY + emH, z).setUv(u1, v1).setColor(r, g, b, a)
            builder.addVertex(matrix, emX + emW,  emY,       z).setUv(u1, v0).setColor(r, g, b, a)
            builder.addVertex(matrix, emX,        emY,       z).setUv(u0, v0).setColor(r, g, b, a)
            quadsEmitted++
        }

        val mesh = builder.build()
        if (mesh != null) BufferUploader.drawWithShader(mesh)
        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
    }

    /**
     * Draws chemical-kind [MonitorBlockEntity.IconOverlay]s as tinted textured quads
     * off Mekanism's chemical atlas. Uses [MekanismChemicalBridge] for reflection-based
     * lookup so the renderer compiles without Mekanism on the classpath; absent
     * Mekanism, each icon draws as a no-op.
     *
     * Atlas per sprite: unlike fluids (shared block atlas), chemicals live on
     * Mekanism's own chemical atlas. The sprite carries its atlas reference via
     * [TextureAtlasSprite.atlasLocation], so we bind that texture per-icon rather
     * than assuming a single atlas up front. In practice all chemicals share one
     * atlas, but binding per sprite is cheap and future-proofs against split atlases.
     */
    private fun renderChemicalIcons(
        poseStack: PoseStack,
        icons: List<MonitorBlockEntity.IconOverlay>,
        pxW: Int,
        pxH: Int,
        surfaceW: Float,
        surfaceH: Float,
    ) {
        if (pxW <= 0 || pxH <= 0) return
        if (!MekanismChemicalBridge.isAvailable()) return
        val matrix = poseStack.last().pose()

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
        RenderSystem.depthMask(false)
        RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }

        // Group by atlas so a single draw call handles each atlas binding.
        // Mekanism ships one chemical atlas today, but group-by keeps us honest
        // if they ever split into gas/pigment/infuse atlases.
        val grouped = LinkedHashMap<net.minecraft.resources.ResourceLocation,
            MutableList<Pair<MonitorBlockEntity.IconOverlay, MekanismChemicalBridge.Resolved>>>()
        for (icon in icons) {
            val resolved = MekanismChemicalBridge.resolve(icon.id) ?: continue
            val atlasLoc = resolved.sprite.atlasLocation()
            grouped.getOrPut(atlasLoc) { mutableListOf() }.add(icon to resolved)
        }

        for ((atlasLoc, bucket) in grouped) {
            RenderSystem.setShaderTexture(0, atlasLoc)
            val builder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
            for ((icon, resolved) in bucket) {
                val sprite = resolved.sprite
                val tint = resolved.tint
                val emX = (icon.x.toFloat() / pxW) * surfaceW
                val emY = (icon.y.toFloat() / pxH) * surfaceH
                val emW = (icon.wPx.toFloat() / pxW) * surfaceW
                val emH = (icon.hPx.toFloat() / pxH) * surfaceH
                val u0 = sprite.u0; val u1 = sprite.u1
                val v0 = sprite.v0; val v1 = sprite.v1
                val ta = (tint ushr 24) and 0xFF
                val r = (tint ushr 16) and 0xFF
                val g = (tint ushr 8) and 0xFF
                val b = tint and 0xFF
                // Same "alpha=0 ⇒ fully opaque" rule as fluids — Mekanism stores
                // some chemicals with 0xFFRRGGBB and others with 0x00RRGGBB.
                val a = if (ta == 0) 255 else ta
                val z = ICON_Z_LIFT
                builder.addVertex(matrix, emX,       emY + emH, z).setUv(u0, v1).setColor(r, g, b, a)
                builder.addVertex(matrix, emX + emW, emY + emH, z).setUv(u1, v1).setColor(r, g, b, a)
                builder.addVertex(matrix, emX + emW, emY,       z).setUv(u1, v0).setColor(r, g, b, a)
                builder.addVertex(matrix, emX,       emY,       z).setUv(u0, v0).setColor(r, g, b, a)
            }
            val mesh = builder.build()
            if (mesh != null) BufferUploader.drawWithShader(mesh)
        }

        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
    }

    /**
     * Glyph quads via vanilla `position_tex_color` shader (shader-pack-safe).
     *
     * Tries the CPU-rasterized MSDF atlas first ([MsdfRasterAtlas], 12×26 cells,
     * ~3× sharper than the fallback). If that isn't ready yet or generation failed,
     * falls back to the original 5×8 bitmap atlas. Both use identical UV math
     * (code → grid cell) so the same loop handles both.
     */
    private fun renderTextBitmap(
        bufferSource: MultiBufferSource,
        matrix: org.joml.Matrix4f,
        snap: MonitorBlockEntity.RenderSnapshot,
        rowHeight: Float,
        cellWidth: Float,
    ) {
        // Prefer the 12×26 CPU-rasterized MSDF atlas (JBMono) — much sharper
        // than the 5×8 Spleen bitmap fallback. The raster atlas uses vanilla
        // `position_tex_color` (same shader as the bitmap path), so it works
        // under shaderpacks AND in our immediate-mode draw below.
        val rasterLoc = MsdfRasterAtlas.getOrGenerate()
        val useRaster = rasterLoc != null
        val atlasLoc = rasterLoc ?: MsdfShaders.BITMAP_ATLAS
        val atlasW = if (useRaster) MsdfRasterAtlas.ATLAS_W else MsdfShaders.BITMAP_ATLAS_W
        val atlasH = if (useRaster) MsdfRasterAtlas.ATLAS_H else MsdfShaders.BITMAP_ATLAS_H
        val atlasCols = if (useRaster) MsdfRasterAtlas.ATLAS_COLS else MsdfShaders.BITMAP_ATLAS_COLS
        val cellPxW = if (useRaster) MsdfRasterAtlas.CELL_W.toFloat() else MsdfShaders.BITMAP_CELL_PX_W.toFloat()
        val cellPxH = if (useRaster) MsdfRasterAtlas.CELL_H.toFloat() else MsdfShaders.BITMAP_CELL_PX_H.toFloat()

        // DIAG 2026-04-22: IMMEDIATE-MODE text draw, bypassing `bufferSource`.
        // Under Iris's FullyBufferedMultiBufferSource (DW20+Iris), BER-submitted
        // RenderTypes never make it to screen — HD pixel layer only works
        // because it already uses immediate mode. Mirror that pattern for text.
        com.mojang.blaze3d.systems.RenderSystem.setShader {
            net.minecraft.client.renderer.GameRenderer.getPositionTexColorShader()
        }
        // Use the ResourceLocation overload so MC's textureManager lazy-loads the PNG.
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, atlasLoc)
        com.mojang.blaze3d.systems.RenderSystem.enableBlend()
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc()
        com.mojang.blaze3d.systems.RenderSystem.disableCull()
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest()
        com.mojang.blaze3d.systems.RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL)
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false)

        val builder = com.mojang.blaze3d.vertex.Tesselator.getInstance()
            .begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
            )
        var quadsEmitted = 0
        var skippedNonPrintable = 0
        var sumAlpha = 0L
        for (rowIdx in snap.rows.indices) {
            val line = snap.rows[rowIdx]
            val y0 = rowIdx * rowHeight
            val y1 = y0 + rowHeight
            for (col in line.indices) {
                val ch = line[col]
                if (ch == ' ') continue
                val code = ch.code
                if (code !in 0x20..0xFF) { skippedNonPrintable++; continue }
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
                builder.addVertex(matrix, x0, y1, 0f).setUv(u0, v1).setColor(r, g, b, a)
                builder.addVertex(matrix, x1, y1, 0f).setUv(u1, v1).setColor(r, g, b, a)
                builder.addVertex(matrix, x1, y0, 0f).setUv(u1, v0).setColor(r, g, b, a)
                builder.addVertex(matrix, x0, y0, 0f).setUv(u0, v0).setColor(r, g, b, a)
                quadsEmitted++
                sumAlpha += a
            }
        }
        val mesh = builder.build()
        if (mesh != null) {
            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh)
        }
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true)
        com.mojang.blaze3d.systems.RenderSystem.enableCull()

        if (com.brewingcoder.oc2.client.screen.MonitorFrameCounter.current() % 60 == 0L) {
            val avgA = if (quadsEmitted > 0) sumAlpha / quadsEmitted else 0
            // Capture first glyph's vertex coords to verify geometry is in the drawable box
            var sampleCoords = "none"
            loop@ for (rowIdx in snap.rows.indices) {
                for (col in snap.rows[rowIdx].indices) {
                    val ch = snap.rows[rowIdx][col]
                    if (ch != ' ') {
                        val x0 = col * cellWidth
                        val y0 = rowIdx * rowHeight
                        sampleCoords = "ch='$ch' at x=$x0..${x0 + cellWidth} y=$y0..${y0 + rowHeight}"
                        break@loop
                    }
                }
            }
            com.brewingcoder.oc2.OpenComputers2.LOGGER.info(
                "MonitorRenderer.DIAG.bitmap.IMMEDIATE: quads=$quadsEmitted skipped=$skippedNonPrintable avgA=$avgA meshNull=${mesh == null} rowH=$rowHeight cellW=$cellWidth first=$sampleCoords"
            )
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
        private const val ICON_Z_LIFT = 0.5f          // em — push item overlays above text/pixels; empirically a half-em is enough to avoid z-fighting with glyph strokes
        private const val CHAR_ADVANCE_EM = 0.6f     // JBMono Regular's monospace advance
        private const val WORLD_SCREEN_PX_RANGE = 8f // empirically clean across normal viewing distances
        private const val MARGIN_WORLD = 0.04f       // ~4cm bezel; ~4% of a block face
    }
}
