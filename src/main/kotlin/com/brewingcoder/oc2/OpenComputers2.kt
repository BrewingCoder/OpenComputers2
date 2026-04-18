package com.brewingcoder.oc2

import com.brewingcoder.oc2.block.ModBlockEntities
import com.brewingcoder.oc2.block.ModBlocks
import com.brewingcoder.oc2.client.OC2ClientConfig
import com.brewingcoder.oc2.item.ModItems
import com.brewingcoder.oc2.item.ModTabs
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModList
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.runForDist

/**
 * OpenComputers2 — Kotlin/NeoForge port of the OpenComputers spirit.
 * See docs/ for full architecture and design.
 */
@Mod(OpenComputers2.ID)
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
object OpenComputers2 {
    const val ID = "oc2"

    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        LOGGER.info("OpenComputers2 booting…")

        ModBlocks.REGISTRY.register(MOD_BUS)
        ModBlockEntities.REGISTRY.register(MOD_BUS)
        ModItems.REGISTRY.register(MOD_BUS)
        ModTabs.REGISTRY.register(MOD_BUS)

        // Register the client config — backing toml lives at config/oc2-client.toml
        // and players can edit values without rebuilding the mod.
        ModList.get().getModContainerById(ID).orElseThrow().registerConfig(
            ModConfig.Type.CLIENT,
            OC2ClientConfig.SPEC,
        )

        runForDist(
            clientTarget = { MOD_BUS.addListener(::onClientSetup) },
            serverTarget = { MOD_BUS.addListener(::onServerSetup) },
        )
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.info("OC2 client setup")
    }

    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info("OC2 server setup")
    }

    @SubscribeEvent
    fun onCommonSetup(event: FMLCommonSetupEvent) {
        LOGGER.info("OC2 common setup — hello, world.")
    }
}
