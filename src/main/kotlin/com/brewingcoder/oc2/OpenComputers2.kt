package com.brewingcoder.oc2

import com.brewingcoder.oc2.block.ModBlockEntities
import com.brewingcoder.oc2.block.ModBlocks
import com.brewingcoder.oc2.block.parts.ModParts
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

    /** Build ID injected at compile time — format "modVersion.yyyyMMdd.HHmm". */
    val BUILD_ID: String = runCatching {
        OpenComputers2::class.java.getResourceAsStream("/META-INF/build.properties")
            ?.use { java.util.Properties().also { p -> p.load(it) }.getProperty("build.id") }
    }.getOrNull() ?: "dev"

    init {
        // Expose build ID as a system property so OC2Debug (same JVM, different classloader)
        // can read it without reflection across classloader boundaries.
        System.setProperty("oc2.buildId", BUILD_ID)
        LOGGER.info("OpenComputers2 booting… build=$BUILD_ID")

        ModBlocks.REGISTRY.register(MOD_BUS)
        ModBlockEntities.REGISTRY.register(MOD_BUS)
        ModItems.REGISTRY.register(MOD_BUS)
        ModTabs.REGISTRY.register(MOD_BUS)

        // Part type registry — lookups happen during BE NBT load, must be ready
        // before any world loads. No DeferredRegister; static map.
        ModParts.register()

        // Wire NetworkInboxes deliveries to also fan out as `network_message`
        // script events. Set once at mod init; the BE layer doesn't need to know.
        com.brewingcoder.oc2.platform.network.NetworkInboxes.onDelivery = { computerId, msg ->
            com.brewingcoder.oc2.event.EventDispatch.fireToComputerId(
                computerId,
                com.brewingcoder.oc2.platform.script.ScriptEvent(
                    "network_message",
                    listOf(msg.from, msg.body),
                ),
            )
        }

        // Register the client config — backing toml lives at config/oc2-client.toml
        // and players can edit values without rebuilding the mod.
        val modContainer = ModList.get().getModContainerById(ID).orElseThrow()
        modContainer.registerConfig(
            ModConfig.Type.CLIENT,
            OC2ClientConfig.SPEC,
        )
        // Common config — network fetch toggles (pastebin/gist) + tunables. Pushed
        // into platform.http.NetworkFetchPolicy on load/reload via onConfigEvent.
        modContainer.registerConfig(
            ModConfig.Type.COMMON,
            OC2CommonConfig.SPEC,
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

    /** Called on both initial load and `/reload`. Pushes common-config values to the pure-Kotlin policy holder. */
    @SubscribeEvent
    fun onConfigLoad(event: net.neoforged.fml.event.config.ModConfigEvent.Loading) {
        if (event.config.spec === OC2CommonConfig.SPEC) OC2CommonConfig.push()
    }

    @SubscribeEvent
    fun onConfigReload(event: net.neoforged.fml.event.config.ModConfigEvent.Reloading) {
        if (event.config.spec === OC2CommonConfig.SPEC) OC2CommonConfig.push()
    }

    /**
     * Wire the WiFi Extender's internal FE buffer to NeoForge's block capability
     * lookup so adjacent cables/adapters can push energy into it. Registered on
     * the MOD bus because capabilities are a registry-like concern — they need
     * to be ready before any block state gets queried.
     */
    @SubscribeEvent
    fun onRegisterCapabilities(event: net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent) {
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
            ModBlockEntities.WIFI_EXTENDER.get(),
        ) { be, _ -> be.energyStorage }
    }
}
