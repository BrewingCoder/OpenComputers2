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
            val (r, g, b) = colorFor(kind)
            drawBumpOnFace(matrix, buffer, face, r, g, b)
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
        else        -> Triple(160, 160, 160)  // unknown grey
    }

    private operator fun FloatArray.component1(): Float = this[0]
    private operator fun FloatArray.component2(): Float = this[1]
    private operator fun FloatArray.component3(): Float = this[2]
    private operator fun FloatArray.component4(): Float = this[3]
    private operator fun FloatArray.component5(): Float = this[4]
    private operator fun FloatArray.component6(): Float = this[5]

    companion object {
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
