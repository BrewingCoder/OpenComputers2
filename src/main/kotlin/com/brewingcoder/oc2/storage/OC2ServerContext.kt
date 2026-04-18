package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.storage.StorageProvider
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent

/**
 * Server-scoped holder for OC2's per-world infrastructure (storage provider, ID
 * assigner). Created on [ServerStartingEvent] and torn down on
 * [ServerStoppingEvent] so we never leak a previous world's paths into a new one.
 *
 * Mirrors CC:Tweaked's `ServerContext` pattern. We register on the **GAME** bus
 * (NeoForge default for `@EventBusSubscriber`) — server lifecycle events fire there.
 */
@EventBusSubscriber(modid = OpenComputers2.ID)
object OC2ServerContext {
    private const val FOLDER_NAME = OpenComputers2.ID  // "oc2" — yields <world>/oc2/

    private var holder: Holder? = null

    private data class Holder(
        val server: MinecraftServer,
        val storageProvider: WorldStorageProvider,
        val idAssigner: ComputerIdAssigner,
    )

    /** Throws if accessed off-server (no world loaded). Callers guard with `level?.server`. */
    fun get(server: MinecraftServer): Accessor {
        val h = holder ?: error("OC2ServerContext accessed before ServerStartingEvent")
        check(h.server === server) { "OC2ServerContext server mismatch" }
        return Accessor(h.storageProvider, h.idAssigner)
    }

    class Accessor internal constructor(
        val storageProvider: StorageProvider,
        private val idAssigner: ComputerIdAssigner,
    ) {
        /** Counter-only assignment. Prefer [assignComputerIdAt] for blocks — it's crash-safe. */
        fun assignComputerId(): Int = idAssigner.assign("computer")

        /**
         * Crash-safe id allocation for a block at a specific position. Idempotent —
         * if the same `(dimension, blockPos)` was assigned before, returns the same id
         * even if the BE's NBT was lost mid-crash. New positions get a fresh id.
         */
        fun assignComputerIdAt(dimension: String, blockPos: net.minecraft.core.BlockPos): Int =
            idAssigner.assignFor("computer", "$dimension.${blockPos.x}_${blockPos.y}_${blockPos.z}")
    }

    @SubscribeEvent
    fun onServerStarting(event: ServerStartingEvent) {
        val server = event.server
        val root = server.getWorldPath(LevelResource(FOLDER_NAME))
        val provider = WorldStorageProvider(root, WorldStorageProvider.DEFAULT_CAPACITY_BYTES)
        val assigner = ComputerIdAssigner(root.resolve("ids.json"))
        holder = Holder(server, provider, assigner)
        OpenComputers2.LOGGER.info("OC2 server context initialized at {}", root)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        if (holder?.server === event.server) {
            holder = null
            OpenComputers2.LOGGER.info("OC2 server context torn down")
        }
    }
}
