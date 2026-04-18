package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.client.screen.ComputerScreen
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

/**
 * Client-only entry points. Kept in its own object so that calls from common
 * code (e.g. ComputerBlock.useWithoutItem) can be guarded by `level.isClientSide`
 * and the JVM only loads this class on the client side.
 */
object ClientHandler {
    fun openComputerScreen(level: Level, pos: BlockPos) {
        val be = level.getBlockEntity(pos) as? ComputerBlockEntity
            ?: run {
                OpenComputers2.LOGGER.warn("openComputerScreen called on non-Computer @ {}", pos)
                return
            }
        Minecraft.getInstance().setScreen(ComputerScreen(be))
    }
}
