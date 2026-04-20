package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.MonitorBlockEntity
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts actual frames so [MonitorRenderer] can dedupe its render() calls.
 * Iris/Oculus call BlockEntityRenderer.render multiple times per visible
 * frame (gbuffer, shadow, deferred) — without dedup our text gets baked
 * into wrong intermediate buffers.
 *
 * Pattern lifted from CC:Tweaked's `FrameInfo`.
 */
@EventBusSubscriber(modid = OpenComputers2.ID, value = [Dist.CLIENT])
object MonitorFrameCounter {

    init {
        OpenComputers2.LOGGER.info("MonitorFrameCounter: init")
    }

    private val frame: AtomicLong = AtomicLong(0)
    private val lastByBe: WeakHashMap<MonitorBlockEntity, Long> = WeakHashMap()

    fun current(): Long = frame.get()

    @Synchronized
    fun shouldSkip(be: MonitorBlockEntity): Boolean {
        val now = frame.get()
        val last = lastByBe[be]
        if (last == now) return true
        lastByBe[be] = now
        return false
    }

    @SubscribeEvent
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            frame.incrementAndGet()
        }
    }
}
