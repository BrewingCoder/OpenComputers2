package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.block.AdapterBlockEntity
import com.brewingcoder.oc2.client.screen.PartConfigScreen
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Client-only entry points for adapter UIs. Mirrors [ClientHandler] but for
 * the Adapter block — kept separate so AdapterBlock's `level.isClientSide`
 * branch only loads client classes on the client side.
 */
object AdapterClientHandler {
    fun openPartConfigScreen(pos: BlockPos, face: Direction, kind: String, currentLabel: String) {
        // Per-part config (channel + accessSide + per-kind options) comes from
        // the client BE; falls back to defaults if the BE/part has rolled off.
        val mc = Minecraft.getInstance()
        val be = mc.level?.getBlockEntity(pos) as? AdapterBlockEntity
        val part = be?.partOn(face)
        val partChannel = part?.channelId ?: AdapterBlockEntity.DEFAULT_CHANNEL
        val accessSide = (part as? com.brewingcoder.oc2.platform.parts.CapabilityBackedPart<*>)?.accessSide ?: ""
        val options = part?.options?.toMap() ?: emptyMap()
        val data = part?.data ?: ""
        mc.setScreen(PartConfigScreen(pos, face, kind, currentLabel, partChannel, accessSide, options, data))
    }
}
