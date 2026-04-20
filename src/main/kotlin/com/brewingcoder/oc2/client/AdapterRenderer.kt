package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.block.AdapterBlockEntity
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import org.joml.Matrix4f

/**
 * Renders the kind-colored part bumps on each populated face of an
 * [AdapterBlockEntity]. The cable hub + arms come from the static block model
 * (driven by `conn_<face>` blockstate properties); only the part-kind colors
 * live here so we can keep blockstate to 64 variants instead of 5⁶ = 15625.
 *
 * Geometry: an 8×8×3 voxel box bumping outward from the arm tip. Position is
 * fixed per-face; color is per-kind (see [colorFor]).
 */
class AdapterRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<AdapterBlockEntity> {

    override fun render(
        be: AdapterBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val parts = be.renderSnapshot()
        if (parts.isEmpty()) return
        val buffer = bufferSource.getBuffer(SOLID_FILL)
        val matrix = poseStack.last().pose()
        for ((face, kind) in parts) {
            // Body: dark grey to match cable core. Trim: kind-color frame on
            // the outward face PLUS a side band wrapping the perimeter at
            // mid-depth. Both together make the kind read clearly from any
            // viewing angle (front view = frame; side view = band).
            drawBumpOnFace(matrix, buffer, face, BODY_R, BODY_G, BODY_B)
            val (tr, tg, tb) = colorFor(kind)
            drawTrimOnFace(matrix, buffer, face, tr, tg, tb)
            drawSideBand(matrix, buffer, face, tr, tg, tb)
        }
    }

    /**
     * Draw the 5 visible quads of an outward-bumping box on [face]. Inner face
     * (toward block center) hidden against the cable arm. Box: 8×8 cross-section,
     * 3 voxels deep, centered on the face.
     */
    private fun drawBumpOnFace(
        matrix: Matrix4f,
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        face: Direction,
        r: Int, g: Int, b: Int,
    ) {
        val (x0, y0, z0, x1, y1, z1) = bumpBox(face)
        val a = 255
        // Six faces of the box; emit only the 5 outward-visible ones (skip the
        // inward face that touches the cable arm).
        // -X face
        if (face != Direction.EAST) {
            buf.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a)
        }
        // +X face
        if (face != Direction.WEST) {
            buf.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a)
        }
        // -Y face
        if (face != Direction.UP) {
            buf.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a)
        }
        // +Y face
        if (face != Direction.DOWN) {
            buf.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a)
        }
        // -Z face
        if (face != Direction.SOUTH) {
            buf.addVertex(matrix, x1, y0, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y1, z0).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y1, z0).setColor(r, g, b, a)
        }
        // +Z face
        if (face != Direction.NORTH) {
            buf.addVertex(matrix, x0, y0, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y0, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a)
            buf.addVertex(matrix, x0, y1, z1).setColor(r, g, b, a)
        }
    }

    /**
     * Draw a 1-voxel-wide colored frame on the OUTWARD face of the bump,
     * offset slightly outward to avoid z-fighting with the body. Frame is the
     * kind indicator; the body underneath stays adapter-grey.
     *
     * Geometry: 6×6 outward face → 4 thin quads forming a hollow square,
     * leaving a 4×4 dark center.
     */
    private fun drawTrimOnFace(
        matrix: Matrix4f,
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        face: Direction,
        r: Int, g: Int, b: Int,
    ) {
        val box = bumpBox(face)
        val a = 255
        val eps = 0.0015f  // outward offset, ~1/700 voxel — invisible but z-fighting-proof
        // Compute the 4 trim rectangles in face-local 2D coords (u,v) and
        // project them onto the outward face's plane in world coords. Use
        // a tiny helper that handles the 6 face orientations.
        val (u0, v0, u1, v1, plane, planeOffset) = trimRect(face, box, eps)
        val w = u1 - u0
        val h = v1 - v0
        val t = 1f / 16f  // 1-voxel trim thickness
        // Top edge   (u0..u1, v1-t..v1)
        emitTrimQuad(matrix, buf, face, u0, v1 - t, u1, v1, plane, planeOffset, r, g, b, a)
        // Bottom edge (u0..u1, v0..v0+t)
        emitTrimQuad(matrix, buf, face, u0, v0, u1, v0 + t, plane, planeOffset, r, g, b, a)
        // Left edge   (u0..u0+t, v0+t..v1-t)  — exclude corners (already drawn)
        emitTrimQuad(matrix, buf, face, u0, v0 + t, u0 + t, v1 - t, plane, planeOffset, r, g, b, a)
        // Right edge  (u1-t..u1, v0+t..v1-t)
        emitTrimQuad(matrix, buf, face, u1 - t, v0 + t, u1, v1 - t, plane, planeOffset, r, g, b, a)
        @Suppress("UNUSED_VARIABLE") val unused = w + h  // suppress "unused" warning
    }

    /**
     * Compute the (u, v) bounds of the bump's outward face plus the projection
     * plane (axis index 0=x, 1=y, 2=z) and the world-space offset along that
     * plane (where to draw the trim). Eps shifts outward so trim sits ON TOP of
     * the body face without z-fighting.
     */
    private fun trimRect(face: Direction, box: FloatArray, eps: Float): TrimSpec = when (face) {
        // outward face coords are (u=x, v=y), plane=z (axis 2)
        Direction.NORTH -> TrimSpec(box[0], box[1], box[3], box[4], 2, box[2] - eps)  // -Z outward
        Direction.SOUTH -> TrimSpec(box[0], box[1], box[3], box[4], 2, box[5] + eps)  // +Z outward
        // outward face coords are (u=y, v=z), plane=x
        Direction.WEST  -> TrimSpec(box[1], box[2], box[4], box[5], 0, box[0] - eps)  // -X
        Direction.EAST  -> TrimSpec(box[1], box[2], box[4], box[5], 0, box[3] + eps)  // +X
        // outward face coords are (u=x, v=z), plane=y
        Direction.DOWN  -> TrimSpec(box[0], box[2], box[3], box[5], 1, box[1] - eps)  // -Y
        Direction.UP    -> TrimSpec(box[0], box[2], box[3], box[5], 1, box[4] + eps)  // +Y
    }

    private data class TrimSpec(
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val plane: Int,           // 0=x, 1=y, 2=z
        val planeOffset: Float,   // where on that axis the trim sits
    )
    private operator fun TrimSpec.component1(): Float = u0
    private operator fun TrimSpec.component2(): Float = v0
    private operator fun TrimSpec.component3(): Float = u1
    private operator fun TrimSpec.component4(): Float = v1
    private operator fun TrimSpec.component5(): Int = plane
    private operator fun TrimSpec.component6(): Float = planeOffset

    private fun emitTrimQuad(
        matrix: Matrix4f,
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        face: Direction,
        u0: Float, v0: Float, u1: Float, v1: Float,
        plane: Int, planeOffset: Float,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        // Map (u, v, plane) back to world (x, y, z). Winding order matters so
        // the outward normal points OUT (away from the block center).
        // Outward direction for each face: NORTH = -Z, SOUTH = +Z, WEST = -X,
        // EAST = +X, DOWN = -Y, UP = +Y. We pick CCW winding when viewed from
        // outside the block.
        when (face) {
            Direction.NORTH -> {
                // outward = -Z. CCW from -Z view: (u0,v0) → (u1,v0) → (u1,v1) → (u0,v1) reversed
                buf.addVertex(matrix, u0, v0, planeOffset).setColor(r, g, b, a)
                buf.addVertex(matrix, u0, v1, planeOffset).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, v1, planeOffset).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, v0, planeOffset).setColor(r, g, b, a)
            }
            Direction.SOUTH -> {
                buf.addVertex(matrix, u0, v0, planeOffset).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, v0, planeOffset).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, v1, planeOffset).setColor(r, g, b, a)
                buf.addVertex(matrix, u0, v1, planeOffset).setColor(r, g, b, a)
            }
            Direction.WEST -> {
                buf.addVertex(matrix, planeOffset, u0, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, planeOffset, u1, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, planeOffset, u1, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, planeOffset, u0, v1).setColor(r, g, b, a)
            }
            Direction.EAST -> {
                buf.addVertex(matrix, planeOffset, u0, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, planeOffset, u0, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, planeOffset, u1, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, planeOffset, u1, v0).setColor(r, g, b, a)
            }
            Direction.DOWN -> {
                buf.addVertex(matrix, u0, planeOffset, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, u0, planeOffset, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, planeOffset, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, planeOffset, v0).setColor(r, g, b, a)
            }
            Direction.UP -> {
                buf.addVertex(matrix, u0, planeOffset, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, planeOffset, v0).setColor(r, g, b, a)
                buf.addVertex(matrix, u1, planeOffset, v1).setColor(r, g, b, a)
                buf.addVertex(matrix, u0, planeOffset, v1).setColor(r, g, b, a)
            }
        }
    }

    /**
     * Draw a thin colored band wrapping the 4 SIDE faces of the bump at
     * mid-depth. Together with the outward-face frame this gives the kind a
     * clear read from any angle (frame visible head-on, band visible from
     * grazing / side angles).
     *
     * For a 3-voxel-deep bump, the band sits at mid-depth and is 0.25
     * voxels (≈1/64 world units) tall — thin enough to read as a clean
     * accent line without dominating the bump silhouette.
     */
    private fun drawSideBand(
        matrix: Matrix4f,
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        face: Direction,
        r: Int, g: Int, b: Int,
    ) {
        val box = bumpBox(face)
        val a = 255
        val eps = 0.0015f
        val x0 = box[0]; val y0 = box[1]; val z0 = box[2]
        val x1 = box[3]; val y1 = box[4]; val z1 = box[5]
        // Bump depth axis = the face's axis; pick the mid 1/16 slab.
        when (face) {
            Direction.NORTH, Direction.SOUTH -> {
                // depth axis = Z. Mid-Z slab: 1 voxel centered on (z0+z1)/2.
                val mz = (z0 + z1) / 2f
                val bz0 = mz - 0.125f / 16f
                val bz1 = mz + 0.125f / 16f
                // -X face strip
                emitFlatQuad(matrix, buf, axis = 0, planeOffset = x0 - eps,
                    u0 = y0, v0 = bz0, u1 = y1, v1 = bz1, ccwFromOutside = false, r, g, b, a)
                // +X face strip
                emitFlatQuad(matrix, buf, axis = 0, planeOffset = x1 + eps,
                    u0 = y0, v0 = bz0, u1 = y1, v1 = bz1, ccwFromOutside = true, r, g, b, a)
                // -Y face strip
                emitFlatQuad(matrix, buf, axis = 1, planeOffset = y0 - eps,
                    u0 = x0, v0 = bz0, u1 = x1, v1 = bz1, ccwFromOutside = true, r, g, b, a)
                // +Y face strip
                emitFlatQuad(matrix, buf, axis = 1, planeOffset = y1 + eps,
                    u0 = x0, v0 = bz0, u1 = x1, v1 = bz1, ccwFromOutside = false, r, g, b, a)
            }
            Direction.WEST, Direction.EAST -> {
                // depth axis = X.
                val mx = (x0 + x1) / 2f
                val bx0 = mx - 0.125f / 16f
                val bx1 = mx + 0.125f / 16f
                // -Y face strip
                emitFlatQuad(matrix, buf, axis = 1, planeOffset = y0 - eps,
                    u0 = bx0, v0 = z0, u1 = bx1, v1 = z1, ccwFromOutside = true, r, g, b, a)
                // +Y face strip
                emitFlatQuad(matrix, buf, axis = 1, planeOffset = y1 + eps,
                    u0 = bx0, v0 = z0, u1 = bx1, v1 = z1, ccwFromOutside = false, r, g, b, a)
                // -Z face strip
                emitFlatQuad(matrix, buf, axis = 2, planeOffset = z0 - eps,
                    u0 = bx0, v0 = y0, u1 = bx1, v1 = y1, ccwFromOutside = false, r, g, b, a)
                // +Z face strip
                emitFlatQuad(matrix, buf, axis = 2, planeOffset = z1 + eps,
                    u0 = bx0, v0 = y0, u1 = bx1, v1 = y1, ccwFromOutside = true, r, g, b, a)
            }
            Direction.DOWN, Direction.UP -> {
                // depth axis = Y.
                val my = (y0 + y1) / 2f
                val by0 = my - 0.125f / 16f
                val by1 = my + 0.125f / 16f
                // -X face strip
                emitFlatQuad(matrix, buf, axis = 0, planeOffset = x0 - eps,
                    u0 = by0, v0 = z0, u1 = by1, v1 = z1, ccwFromOutside = false, r, g, b, a)
                // +X face strip
                emitFlatQuad(matrix, buf, axis = 0, planeOffset = x1 + eps,
                    u0 = by0, v0 = z0, u1 = by1, v1 = z1, ccwFromOutside = true, r, g, b, a)
                // -Z face strip
                emitFlatQuad(matrix, buf, axis = 2, planeOffset = z0 - eps,
                    u0 = x0, v0 = by0, u1 = x1, v1 = by1, ccwFromOutside = false, r, g, b, a)
                // +Z face strip
                emitFlatQuad(matrix, buf, axis = 2, planeOffset = z1 + eps,
                    u0 = x0, v0 = by0, u1 = x1, v1 = by1, ccwFromOutside = true, r, g, b, a)
            }
        }
    }

    /**
     * Emit a flat colored quad on a single-axis plane. [axis] selects which
     * world axis is constant (0=X, 1=Y, 2=Z) and [planeOffset] is its value.
     * (u, v) span the other two axes in axis-cyclic order:
     *   axis=0 → u=Y, v=Z
     *   axis=1 → u=X, v=Z
     *   axis=2 → u=X, v=Y
     * [ccwFromOutside] picks winding so the visible side faces away from the
     * block center.
     */
    private fun emitFlatQuad(
        matrix: Matrix4f,
        buf: com.mojang.blaze3d.vertex.VertexConsumer,
        axis: Int, planeOffset: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        ccwFromOutside: Boolean,
        r: Int, g: Int, b: Int, a: Int,
    ) {
        // Build the 4 corners in (u, v) and project to world (x, y, z) per axis.
        // Two winding orders: [a, b, c, d] vs [a, d, c, b] for normal flip.
        val pts = if (ccwFromOutside) arrayOf(
            floatArrayOf(u0, v0), floatArrayOf(u1, v0), floatArrayOf(u1, v1), floatArrayOf(u0, v1)
        ) else arrayOf(
            floatArrayOf(u0, v0), floatArrayOf(u0, v1), floatArrayOf(u1, v1), floatArrayOf(u1, v0)
        )
        for (p in pts) {
            val (x, y, z) = when (axis) {
                0 -> Triple(planeOffset, p[0], p[1])
                1 -> Triple(p[0], planeOffset, p[1])
                else -> Triple(p[0], p[1], planeOffset)
            }
            buf.addVertex(matrix, x, y, z).setColor(r, g, b, a)
        }
    }

    /**
     * 6×6×3 voxel cap positioned on [face]. Returns world-space coordinates
     * (in block units, 0..1) as (x0, y0, z0, x1, y1, z1).
     *
     * Cross-section matches the cable core (6×6); 3-voxel depth sits at the
     * outer end of the arm. Result: clean "module on a cable tip" silhouette.
     */
    private fun bumpBox(face: Direction): FloatArray = when (face) {
        Direction.NORTH -> floatArrayOf(5f/16f, 5f/16f, 0f/16f, 11f/16f, 11f/16f, 3f/16f)
        Direction.SOUTH -> floatArrayOf(5f/16f, 5f/16f,13f/16f, 11f/16f, 11f/16f,16f/16f)
        Direction.WEST  -> floatArrayOf(0f/16f, 5f/16f, 5f/16f,  3f/16f, 11f/16f,11f/16f)
        Direction.EAST  -> floatArrayOf(13f/16f,5f/16f, 5f/16f, 16f/16f, 11f/16f,11f/16f)
        Direction.DOWN  -> floatArrayOf(5f/16f, 0f/16f, 5f/16f, 11f/16f,  3f/16f,11f/16f)
        Direction.UP    -> floatArrayOf(5f/16f,13f/16f, 5f/16f, 11f/16f, 16f/16f,11f/16f)
    }

    /** Map a Part typeId to its bump color (R, G, B). Stable across kinds; single source of truth. */
    private fun colorFor(kind: String): Triple<Int, Int, Int> = when (kind) {
        "inventory" -> Triple(140, 100, 60)   // chest brown
        "redstone"  -> Triple(200,  40, 40)   // dust red
        "fluid"     -> Triple( 60, 100, 200)  // water blue
        "energy"    -> Triple(240, 200, 40)   // FE yellow
        "block"     -> Triple(120, 120, 120)  // cobblestone grey
        "bridge"    -> Triple(180,  60, 200)  // protocol-bridge purple
        else        -> Triple(160, 160, 160)  // unknown grey
    }

    private operator fun FloatArray.component1(): Float = this[0]
    private operator fun FloatArray.component2(): Float = this[1]
    private operator fun FloatArray.component3(): Float = this[2]
    private operator fun FloatArray.component4(): Float = this[3]
    private operator fun FloatArray.component5(): Float = this[4]
    private operator fun FloatArray.component6(): Float = this[5]

    companion object {
        /** Bump body color — matches `adapter_core.png` so the bump visually unifies with the cable. */
        const val BODY_R: Int = 70
        const val BODY_G: Int = 70
        const val BODY_B: Int = 75

        /**
         * Solid-fill render type with vanilla position_color shader. No texture,
         * opaque, no cull (we only emit visible faces but cull state is cheap to
         * leave permissive). Same shape as MsdfShaders.MONITOR_BG_FILL but kept
         * separate so adapter rendering doesn't depend on monitor's shader file.
         */
        val SOLID_FILL: RenderType = RenderType.create(
            "oc2_adapter_part_fill",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                .setCullState(RenderStateShard.NO_CULL)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .createCompositeState(false),
        )
    }
}
