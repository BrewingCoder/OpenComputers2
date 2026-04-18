package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterShadersEvent

/**
 * Owner of the MSDF text shader + the [RenderType] that drives it. Registered
 * via [RegisterShadersEvent] on the mod event bus, client-side only.
 *
 * The shader pair lives at `assets/oc2/shaders/core/msdf_text.{vsh,fsh,json}`.
 * Vertex format is POSITION_TEX_COLOR — same as MC's `position_tex_color`
 * shader, which keeps the attribute layout simple and standard.
 *
 * Atlas texture resource: `oc2:textures/font/jbmono_msdf.png`. The
 * [MsdfTerminalRenderer] sets the `ScreenPxRange` uniform per draw call so the
 * smoothstep edge stays a 1-pixel transition regardless of GUI scale.
 */
@EventBusSubscriber(
    modid = OpenComputers2.ID,
    bus = EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT],
)
object MsdfShaders {

    val ATLAS_TEXTURE: ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/font/jbmono_msdf.png")

    private var shader: ShaderInstance? = null

    fun get(): ShaderInstance? = shader

    /**
     * Solid-color quad render type — used by the monitor renderer to paint per-cell
     * background colors under text. Vanilla `position_color` shader, no texture,
     * translucent + no-cull (same reasoning as MSDF_TEXT).
     */
    val MONITOR_BG_FILL: RenderType = RenderType.create(
        "oc2_monitor_bg_fill",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false),
    )

    /**
     * Render type for MSDF text — a translucent quad pass with our shader bound
     * and the atlas texture as Sampler0. Use a fresh buffer source per frame
     * (graphics.bufferSource()) and end-batch after each text draw.
     */
    val MSDF_TEXT: RenderType = RenderType.create(
        "oc2_msdf_text",
        DefaultVertexFormat.POSITION_TEX_COLOR,
        VertexFormat.Mode.QUADS,
        256,                            // initial buffer size in vertices; grows as needed
        false,                          // crumbling — irrelevant
        true,                           // sortOnUpload — needed for translucency ordering
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shader })
            .setTextureState(RenderStateShard.TextureStateShard(ATLAS_TEXTURE, false, false))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            // NO_CULL so the world-space monitor renderer works for any of the 4 wall
            // facings without per-direction winding gymnastics. The back face is
            // occluded by the underlying opaque block model anyway.
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false),
    )

    @SubscribeEvent
    fun onRegisterShaders(event: RegisterShadersEvent) {
        try {
            event.registerShader(
                ShaderInstance(
                    event.resourceProvider,
                    ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "msdf_text"),
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                ),
            ) { loaded -> shader = loaded }
            OpenComputers2.LOGGER.info("MSDF text shader registered")
        } catch (e: Exception) {
            OpenComputers2.LOGGER.error("Failed to register MSDF text shader", e)
        }
    }
}
