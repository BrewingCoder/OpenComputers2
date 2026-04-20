package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.BlockPartOps
import com.brewingcoder.oc2.block.parts.PartCapabilityKeys
import com.brewingcoder.oc2.block.parts.PartChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.parts.CapabilityKey
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.parts.PartRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Adapter BE — the multi-part block. Holds up to one [Part] per face (6 max);
 * each installed part is registered as its own [PartChannelRegistrant] on the
 * adapter's wifi channel so scripts see them individually
 * (`peripheral.find("inventory")` returns *the* inventory part).
 *
 * Lifecycle:
 *   - On [onLoad]: rehydrate parts from NBT, register each on the channel.
 *   - On [setRemoved]: unregister every part.
 *   - On [neighborChanged]: each part re-resolves its capability lookup so
 *     hot-swapping the adjacent block (chest break/replace) works.
 *
 * NOT a [com.brewingcoder.oc2.platform.ChannelRegistrant] itself — only the
 * parts are. The adapter is just the container.
 *
 * v0 simplifications:
 *   - No labels yet (auto only — label override lands with the part GUI in R2)
 *   - No tick — capability-backed parts are all pull-driven
 *   - No multi-cap-per-face (one part per face, period)
 *   - No client-side cable rendering (visual polish lands in a follow-up)
 */
class AdapterBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.ADAPTER.get(), pos, state) {

    /**
     * Legacy adapter-level channel — kept ONLY to migrate older NBT to the
     * per-part channel model. On load, this value seeds any part that doesn't
     * yet have its own channelId. After migration it's never read again.
     */
    private var legacyChannel: String? = null

    /** Stable per-adapter id used for auto-naming. Assigned lazily on first install. */
    private var adapterId: Int = ID_UNASSIGNED

    /** Installed parts keyed by face. */
    private val parts: MutableMap<Direction, Part> = mutableMapOf()

    /** Per-part registrants kept around so we can unregister cleanly. */
    private val registrants: MutableMap<Direction, PartChannelRegistrant> = mutableMapOf()

    private val registryShouldTrack: Boolean
        get() = level?.isClientSide == false

    // ---------- lifecycle ----------

    override fun onLoad() {
        super.onLoad()
        if (registryShouldTrack) {
            // Rehydrate any parts loaded from NBT — they exist in `parts` but
            // weren't fully initialized (no PartHost while level was null).
            // Migration: any part without its own channel inherits the legacy
            // adapter channel (if NBT had one).
            val migrate = legacyChannel
            if (migrate != null) {
                for (part in parts.values) {
                    if (part.channelId.isBlank() || part.channelId == "default") {
                        part.channelId = migrate
                    }
                }
                legacyChannel = null
            }
            for ((face, part) in parts) {
                part.onAttach(host(face))
                registerPart(face, part)
            }
            recomputeConnections()
            OpenComputers2.LOGGER.info("adapter @ {} loaded with {} parts (per-part channels)",
                blockPos, parts.size)
        }
    }

    override fun setRemoved() {
        if (registryShouldTrack) {
            for ((face, part) in parts) {
                unregisterPart(face)
                part.onDetach()
            }
        }
        super.setRemoved()
    }

    /**
     * Forwarded from the [AdapterBlock]'s `neighborChanged` — each part may
     * need to re-resolve its capability lookup against the new neighbor.
     */
    fun onNeighborChanged() {
        if (!registryShouldTrack) return
        for ((face, part) in parts) {
            part.onNeighborChanged(host(face))
        }
        recomputeConnections()
    }

    // ---------- part install / remove ----------

    /**
     * Install [part] on [face]. Returns true if the slot was free; false if a
     * part already occupies it (caller should drop the item back to the player
     * rather than overwrite). Wires the part into the channel + capability
     * lookup pipeline.
     */
    fun installPart(face: Direction, part: Part): Boolean {
        if (parts.containsKey(face)) return false
        ensureAdapterId()
        parts[face] = part
        if (registryShouldTrack) {
            part.onAttach(host(face))
            registerPart(face, part)
            recomputeConnections()
        }
        setChanged()
        sync()
        OpenComputers2.LOGGER.info("adapter @ {} installed {} on face {} (label='{}')",
            blockPos, part.typeId, face, part.label)
        return true
    }

    /** Remove the part on [face] and return it (so the caller can drop the item). Null if empty. */
    fun removePart(face: Direction): Part? {
        val part = parts.remove(face) ?: return null
        if (registryShouldTrack) {
            unregisterPart(face)
            part.onDetach()
            recomputeConnections()
        }
        setChanged()
        sync()
        OpenComputers2.LOGGER.info("adapter @ {} removed {} from face {}",
            blockPos, part.typeId, face)
        return part
    }

    fun partOn(face: Direction): Part? = parts[face]
    fun installedFaces(): Set<Direction> = parts.keys.toSet()

    /**
     * Update the label of the part on [face] (used by the Part Config GUI).
     * Empty labels fall back to the auto-name. The peripheral wrapper is
     * rebuilt each `asPeripheral()` call from the live label, so renames
     * are picked up without touching [PartChannelRegistrant].
     */
    fun relabelPart(face: Direction, newLabel: String) {
        val part = parts[face] ?: return
        val finalLabel = if (newLabel.isBlank()) {
            "${part.typeId}_${face.serializedName}_${adapterId}"
        } else newLabel
        if (part.label == finalLabel) return
        OpenComputers2.LOGGER.info("adapter @ {} relabeled {} on face {} '{}' -> '{}'",
            blockPos, part.typeId, face, part.label, finalLabel)
        part.label = finalLabel
        setChanged()
        sync()
    }

    /** Snapshot of (face → part typeId) for client-side rendering. Cheap; called per frame. */
    fun renderSnapshot(): Map<Direction, String> =
        parts.mapValues { it.value.typeId }

    /**
     * Replace the part's kind-specific options map with [newOpts]. Persisted
     * to NBT; the part reads its options live via `options[key]`, so config
     * changes (redstone invert, fluid throughput, etc.) take effect on the
     * very next call. No re-registration needed.
     */
    fun setPartOptions(face: Direction, newOpts: Map<String, String>) {
        val part = parts[face] ?: return
        part.options.clear()
        part.options.putAll(newOpts)
        setChanged()
        sync()
    }

    /**
     * Update the access-side override of the part on [face]. Empty = use the
     * install face's opposite (default). Re-resolves the capability so the
     * peripheral surface flips to the new side immediately.
     */
    fun setPartAccessSide(face: Direction, newSide: String) {
        val part = parts[face] ?: return
        val cap = part as? com.brewingcoder.oc2.platform.parts.CapabilityBackedPart<*> ?: return
        if (cap.accessSide == newSide) return
        OpenComputers2.LOGGER.info("adapter @ {} resided {} on face {} '{}' -> '{}'",
            blockPos, part.typeId, face, cap.accessSide.ifBlank { "auto" }, newSide.ifBlank { "auto" })
        cap.accessSide = newSide
        if (registryShouldTrack) cap.onNeighborChanged(host(face))  // re-resolve
        setChanged()
        sync()
    }

    /**
     * Update the channel of the part on [face]. Unregisters the old
     * [PartChannelRegistrant] (which was indexed under the old channel) and
     * re-registers under the new one.
     */
    fun setPartChannel(face: Direction, newChannel: String) {
        val part = parts[face] ?: return
        if (part.channelId == newChannel) return
        OpenComputers2.LOGGER.info("adapter @ {} retuned {} on face {} '{}' -> '{}'",
            blockPos, part.typeId, face, part.channelId, newChannel)
        if (registryShouldTrack) unregisterPart(face)
        part.channelId = newChannel
        if (registryShouldTrack) registerPart(face, part)
        setChanged()
        sync()
    }

    /**
     * Recompute the per-face connection booleans on the blockstate. A face is
     * connected when there's a part on it OR the adjacent block is another
     * adapter (visual-only auto-cabling). Pushes a single setBlock if any value
     * changed — clients pick up the new state via vanilla block-update sync.
     *
     * Server-thread only. Cheap; fixed 6-iteration loop.
     */
    private fun recomputeConnections() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        var newState = blockState
        var anyChanged = false
        for (face in Direction.entries) {
            val prop = AdapterBlock.propertyFor(face)
            val should = parts.containsKey(face) || (lvl.getBlockState(blockPos.relative(face)).block is AdapterBlock)
            if (newState.getValue(prop) != should) {
                newState = newState.setValue(prop, should)
                anyChanged = true
            }
        }
        if (anyChanged) {
            // flag 2: clients only, no neighbor cascade — same lesson as Monitor
            // (avoid save-time cascade that hung the server in earlier work).
            lvl.setBlock(blockPos, newState, 2)
        }
    }

    private fun registerPart(face: Direction, part: Part) {
        val r = PartChannelRegistrant(
            channelId = part.channelId,
            location = Position(blockPos.x, blockPos.y, blockPos.z),
            part = part,
        )
        registrants[face] = r
        ChannelRegistry.register(r)
    }

    private fun unregisterPart(face: Direction) {
        registrants.remove(face)?.let { ChannelRegistry.unregister(it) }
    }

    // ---------- PartHost ----------

    /** One [PartHost] view per face — caches nothing, cheap to allocate. */
    private fun host(face: Direction): PartHost = object : PartHost {
        override val faceId: String = face.serializedName

        override fun defaultLabel(typeId: String): String = "${typeId}_${face.serializedName}_${adapterId}"

        @Suppress("UNCHECKED_CAST")
        override fun <C : Any> lookupCapability(key: CapabilityKey<C>, sideOverride: String?): C? {
            val lvl = level as? net.minecraft.server.level.ServerLevel ?: return null
            val cap = PartCapabilityKeys.resolve(key)
            // Default side = face we install against, opposite (i.e., the side
            // of the neighbor pointing back at us). Override = explicit player
            // choice from the part config GUI.
            val side = if (sideOverride.isNullOrBlank()) face.opposite
                       else Direction.byName(sideOverride) ?: face.opposite
            return lvl.getCapability(cap, blockPos.relative(face), side)
        }

        override fun readRedstoneSignal(): Int {
            val lvl = level ?: return 0
            return lvl.getSignal(blockPos.relative(face), face)
        }

        override fun writeRedstoneSignal(level: Int) {
            // Stub for now — RedstonePart write path lands with that part.
            // Adapter block state doesn't carry per-face signal yet.
        }

        override fun readAdjacentBlock(): com.brewingcoder.oc2.platform.peripheral.BlockPeripheral.BlockReadout? =
            BlockPartOps.read(level, blockPos, face)

        override fun harvestAdjacentBlock(
            target: com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral?,
        ): List<com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot> =
            BlockPartOps.harvest(level, blockPos, face, target)

        override fun adjacentBlockEntity(): Any? {
            val lvl = level ?: return null
            // Same off-thread marshal pattern as BlockPartOps — script worker
            // threads can't safely touch level chunks, so submit to the server
            // thread and block briefly. 5s matches BlockPartOps.
            val target = blockPos.relative(face)
            val server = lvl.server ?: return lvl.getBlockEntity(target)
            if (server.isSameThread) return lvl.getBlockEntity(target)
            return server.submit(Supplier { lvl.getBlockEntity(target) }).get(5, TimeUnit.SECONDS)
        }
    }

    private fun ensureAdapterId() {
        if (adapterId != ID_UNASSIGNED) return
        adapterId = NEXT_ADAPTER_ID.getAndIncrement()
    }

    // ---------- NBT ----------

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        // Per-part channels — adapter has no channel of its own anymore.
        // Legacy NBT_CHANNEL key is no longer written.
        if (adapterId != ID_UNASSIGNED) tag.putInt(NBT_ADAPTER_ID, adapterId)
        val partsTag = CompoundTag()
        for ((face, part) in parts) {
            val partTag = CompoundTag()
            partTag.putString(NBT_PART_TYPE, part.typeId)
            val nested = CompoundTag()
            part.saveNbt(NbtWriterImpl(nested))
            partTag.put(NBT_PART_DATA, nested)
            partsTag.put(face.serializedName, partTag)
        }
        tag.put(NBT_PARTS, partsTag)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        // Capture legacy adapter-level channel so [onLoad] can migrate it down
        // into any per-part channelId that's still defaulted.
        if (tag.contains(NBT_CHANNEL)) legacyChannel = tag.getString(NBT_CHANNEL)
        if (tag.contains(NBT_ADAPTER_ID)) adapterId = tag.getInt(NBT_ADAPTER_ID)
        parts.clear()
        if (tag.contains(NBT_PARTS)) {
            val partsTag = tag.getCompound(NBT_PARTS)
            for (key in partsTag.allKeys) {
                val face = Direction.byName(key) ?: continue
                val partTag = partsTag.getCompound(key)
                val typeId = partTag.getString(NBT_PART_TYPE)
                val type = PartRegistry.get(typeId) ?: run {
                    OpenComputers2.LOGGER.warn(
                        "adapter @ {} dropping part of unknown type '{}' on face {}",
                        blockPos, typeId, face,
                    )
                    continue
                }
                val part = type.create()
                if (partTag.contains(NBT_PART_DATA)) {
                    part.loadNbt(NbtReaderImpl(partTag.getCompound(NBT_PART_DATA)))
                }
                parts[face] = part
            }
        }
    }

    // ---------- sync ----------

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = CompoundTag()
        saveAdditional(tag, registries)
        return tag
    }

    override fun handleUpdateTag(tag: CompoundTag, registries: HolderLookup.Provider) {
        loadAdditional(tag, registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    /** Push the latest tag to clients tracking this chunk. Cheap; called on every install/remove. */
    private fun sync() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        // flag 2: clients only, no neighbor cascade. Same lesson as Monitor (avoid save hangs).
        lvl.sendBlockUpdated(blockPos, blockState, blockState, 2)
    }

    // ---------- NBT bridges ----------

    private class NbtWriterImpl(private val tag: CompoundTag) : Part.NbtWriter {
        override fun putString(key: String, value: String) { tag.putString(key, value) }
        override fun putInt(key: String, value: Int) { tag.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean) { tag.putBoolean(key, value) }
    }

    private class NbtReaderImpl(private val tag: CompoundTag) : Part.NbtReader {
        override fun getString(key: String): String = tag.getString(key)
        override fun getInt(key: String): Int = tag.getInt(key)
        override fun getBoolean(key: String): Boolean = tag.getBoolean(key)
        override fun has(key: String): Boolean = tag.contains(key)
    }

    companion object {
        /** Default channel for any newly installed part. Adapter no longer has its own. */
        const val DEFAULT_CHANNEL = "default"
        const val ID_UNASSIGNED: Int = -1

        private const val NBT_CHANNEL = "channelId"     // legacy — read for migration only
        private const val NBT_ADAPTER_ID = "adapterId"
        private const val NBT_PARTS = "parts"
        private const val NBT_PART_TYPE = "type"
        private const val NBT_PART_DATA = "data"

        private val NEXT_ADAPTER_ID: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger(1)
    }
}
