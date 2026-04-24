package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.MonitorBlock
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RenderHighlightEvent

/**
 * Cancels MC's default "looking at this block" wireframe when the player's
 * crosshair is on a monitor. The wireframe is distracting on a block whose
 * whole face is rendered content. CC:T suppresses its monitor the same way.
 */
@EventBusSubscriber(modid = OpenComputers2.ID, value = [Dist.CLIENT])
object MonitorHighlightSuppressor {

    @SubscribeEvent
    fun onRenderHighlight(event: RenderHighlightEvent.Block) {
        val level = Minecraft.getInstance().level ?: return
        val hit: BlockHitResult = event.target
        if (hit.type != HitResult.Type.BLOCK) return
        if (level.getBlockState(hit.blockPos).block is MonitorBlock) {
            event.isCanceled = true
        }
    }
}
