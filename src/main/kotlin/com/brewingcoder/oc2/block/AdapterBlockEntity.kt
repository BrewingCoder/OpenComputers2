package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
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

    /** Wifi channel the adapter publishes parts on. */
    var channelId: String = DEFAULT_CHANNEL
        private set

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
            for ((face, part) in parts) {
                part.onAttach(host(face))
                registerPart(face, part)
            }
            recomputeConnections()
            OpenComputers2.LOGGER.info("adapter @ {} loaded with {} parts on channel '{}'",
                blockPos, parts.size, channelId)
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

    /** Snapshot of (face → part typeId) for client-side rendering. Cheap; called per frame. */
    fun renderSnapshot(): Map<Direction, String> =
        parts.mapValues { it.value.typeId }

    fun setChannel(newChannel: String) {
        if (newChannel == channelId) return
        if (registryShouldTrack) {
            // Re-register every part on the new channel atomically.
            for (face in parts.keys) unregisterPart(face)
            channelId = newChannel
            for ((face, part) in parts) registerPart(face, part)
        } else {
            channelId = newChannel
        }
        setChanged()
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
            channelId = channelId,
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
        override fun <C : Any> lookupCapability(key: CapabilityKey<C>): C? {
            val lvl = level as? net.minecraft.server.level.ServerLevel ?: return null
            val cap = PartCapabilityKeys.resolve(key)
            // The "side" we pass is the face of the *neighbor* that's facing us,
            // i.e. the opposite of our face direction.
            return lvl.getCapability(cap, blockPos.relative(face), face.opposite)
        }

        override fun readRedstoneSignal(): Int {
            val lvl = level ?: return 0
            return lvl.getSignal(blockPos.relative(face), face)
        }

        override fun writeRedstoneSignal(level: Int) {
            // Stub for now — RedstonePart write path lands with that part.
            // Adapter block state doesn't carry per-face signal yet.
        }
    }

    private fun ensureAdapterId() {
        if (adapterId != ID_UNASSIGNED) return
        adapterId = NEXT_ADAPTER_ID.getAndIncrement()
    }

    // ---------- NBT ----------

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(NBT_CHANNEL, channelId)
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
        if (tag.contains(NBT_CHANNEL)) channelId = tag.getString(NBT_CHANNEL)
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
        const val DEFAULT_CHANNEL = "default"
        const val ID_UNASSIGNED: Int = -1

        private const val NBT_CHANNEL = "channelId"
        private const val NBT_ADAPTER_ID = "adapterId"
        private const val NBT_PARTS = "parts"
        private const val NBT_PART_TYPE = "type"
        private const val NBT_PART_DATA = "data"

        private val NEXT_ADAPTER_ID: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger(1)
    }
}
