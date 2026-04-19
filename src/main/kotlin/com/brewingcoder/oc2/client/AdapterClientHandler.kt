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
        // Part-level channel comes from the client BE's part-on-face. Falls back
        // to default if the BE/part has rolled off (would only happen mid-break).
        val mc = Minecraft.getInstance()
        val be = mc.level?.getBlockEntity(pos) as? AdapterBlockEntity
        val partChannel = be?.partOn(face)?.channelId ?: AdapterBlockEntity.DEFAULT_CHANNEL
        mc.setScreen(PartConfigScreen(pos, face, kind, currentLabel, partChannel))
    }
}
