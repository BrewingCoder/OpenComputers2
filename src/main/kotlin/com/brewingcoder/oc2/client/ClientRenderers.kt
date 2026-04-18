package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ModBlockEntities
import com.brewingcoder.oc2.client.screen.MonitorRenderer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.EntityRenderersEvent

/**
 * Client-side renderer registrations. Today: Monitor BE. Future: any other
 * BE that needs custom rendering (eg. animated displays, peripheral cards).
 *
 * MOD bus, CLIENT-only. The shader/RenderType registration for MSDF lives in
 * [com.brewingcoder.oc2.client.screen.MsdfShaders] — separate event.
 */
@EventBusSubscriber(
    modid = OpenComputers2.ID,
    bus = EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT],
)
object ClientRenderers {

    @SubscribeEvent
    fun onRegisterBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.MONITOR.get()) { ctx -> MonitorRenderer(ctx) }
    }
}
