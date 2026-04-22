package com.brewingcoder.oc2.client.screen

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

/**
 * Render type for the HD pixel layer.
 *
 * Mirrors vanilla `RenderType.text(rl)` (POSITION_COLOR_TEX_LIGHTMAP, text shader,
 * translucent blending) but with depth-write disabled. The pixel layer flushes BEFORE
 * the text path; if it wrote depth, the text bg quads at the same z would fail the
 * depth-LESS test and disappear, and text fg would fail too because Z_FG_OFFSET is
 * scaled down by `xScale` to ~1e-7 world units — below depth-buffer precision.
 *
 * The subclass-of-RenderType pattern is the standard trick (CC:Tweaked uses the same)
 * to reach `RenderStateShard`'s `protected` shards from outside `net.minecraft.*`.
 */
internal object MonitorPixelRenderType : RenderType(
    "oc2_monitor_pixel", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
    VertexFormat.Mode.QUADS, 1024, false, true, {}, {},
) {
    private val INSTANCES: MutableMap<ResourceLocation, RenderType> = HashMap()

    fun get(rl: ResourceLocation): RenderType = INSTANCES.getOrPut(rl) {
        val rt: RenderType = create(
            "oc2_monitor_pixel",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            1024,
            false,
            true,
            CompositeState.builder()
                .setShaderState(RENDERTYPE_TEXT_SHADER)
                .setTextureState(RenderStateShard.TextureStateShard(rl, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(LIGHTMAP)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false),
        )
        rt
    }
}
