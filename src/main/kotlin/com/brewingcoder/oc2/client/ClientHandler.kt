package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.block.MonitorBlockEntity
import com.brewingcoder.oc2.block.WiFiExtenderBlock
import com.brewingcoder.oc2.block.WiFiExtenderBlockEntity
import com.brewingcoder.oc2.block.WiFiExtenderConfig
import com.brewingcoder.oc2.client.screen.ComputerScreen
import com.brewingcoder.oc2.client.screen.MonitorConfigScreen
import com.brewingcoder.oc2.client.screen.WiFiExtenderConfigScreen
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

    fun openMonitorConfigScreen(level: Level, pos: BlockPos) {
        val be = level.getBlockEntity(pos) as? MonitorBlockEntity
            ?: run {
                OpenComputers2.LOGGER.warn("openMonitorConfigScreen called on non-Monitor @ {}", pos)
                return
            }
        Minecraft.getInstance().setScreen(
            MonitorConfigScreen(pos, be.channelId, be.groupBlocksWide to be.groupBlocksTall)
        )
    }

    fun openWiFiExtenderConfigScreen(level: Level, pos: BlockPos) {
        val be = level.getBlockEntity(pos) as? WiFiExtenderBlockEntity
            ?: run {
                OpenComputers2.LOGGER.warn("openWiFiExtenderConfigScreen called on non-Extender @ {}", pos)
                return
            }
        val active = level.getBlockState(pos).getValue(WiFiExtenderBlock.ACTIVE)
        Minecraft.getInstance().setScreen(
            WiFiExtenderConfigScreen(
                pos = pos,
                initialChannel = be.channelId,
                initialEnergy = be.energyStorage.energyStored,
                maxEnergy = WiFiExtenderConfig.bufferFE,
                initialActive = active,
            )
        )
    }
}
