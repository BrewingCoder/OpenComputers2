package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.ExtenderMesh
import com.brewingcoder.oc2.platform.Position
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.energy.EnergyStorage

/**
 * WiFi Extender BE. Registers as kind="extender" on its channel so the rest
 * of the platform can discover mesh participants via [ChannelRegistry].
 *
 * Tick loop:
 *   - If [energyStorage] has ≥ [WiFiExtenderConfig.idleDrawFE] and the channel
 *     is non-blank, drain idle cost and flip blockstate ACTIVE=true.
 *   - Otherwise flip ACTIVE=false. Registry membership persists regardless —
 *     an unpowered extender is still *on* the channel but contributes no
 *     coverage in [ExtenderMesh] since inactive extenders are filtered out at
 *     the [activeExtendersOn] call site.
 *
 * Energy is exposed via NeoForge's [EnergyStorage] capability; registration
 * happens in [com.brewingcoder.oc2.OpenComputers2.onRegisterCapabilities].
 */
class WiFiExtenderBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.WIFI_EXTENDER.get(), pos, state),
    ChannelRegistrant {

    override var channelId: String = DEFAULT_CHANNEL
        private set

    override val location: Position
        get() = Position(blockPos.x, blockPos.y, blockPos.z)

    override val kind: String
        get() = REGISTRANT_KIND

    /**
     * Internal energy buffer. Receives from any adjacent energy-pushing block
     * (cables, adapters with energy parts, mod-specific cells). Buffer size
     * tracks [WiFiExtenderConfig.bufferFE] — rebuilt once on first access so
     * config reloads before first tick take effect.
     */
    val energyStorage: EnergyStorage = EnergyStorage(WiFiExtenderConfig.bufferFE)

    /** Server-only — client BEs are visual stand-ins and don't touch the registry. */
    private val registryShouldTrack: Boolean
        get() = level?.isClientSide == false

    override fun onLoad() {
        super.onLoad()
        if (registryShouldTrack) {
            ChannelRegistry.register(this)
        }
    }

    override fun setRemoved() {
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        super.setRemoved()
    }

    /** Called every server tick by [WiFiExtenderBlock.getTicker]. */
    fun tick() {
        val idleCost = WiFiExtenderConfig.idleDrawFE
        val wantActive = energyStorage.energyStored >= idleCost && channelId.isNotBlank()
        if (wantActive) {
            energyStorage.extractEnergy(idleCost, false)
            setChanged()
        }
        syncActiveState(wantActive)
    }

    /**
     * Reassign channel. Re-registers with [ChannelRegistry] under the new key.
     * Empty/blank channels are coerced to [DEFAULT_CHANNEL] for the same reason
     * Monitor does: prevent an extender from falling off the registry entirely
     * and becoming invisible to any mesh query.
     */
    fun setChannel(newChannel: String) {
        val cleaned = newChannel.trim().take(MAX_CHANNEL_LENGTH).ifBlank { DEFAULT_CHANNEL }
        if (cleaned == channelId) return
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        channelId = cleaned
        if (registryShouldTrack) ChannelRegistry.register(this)
        setChanged()
        sync()
        OpenComputers2.LOGGER.info("wifi extender @ {} channel -> '{}'", blockPos, channelId)
    }

    private fun syncActiveState(active: Boolean) {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        val current = blockState
        if (current.getValue(WiFiExtenderBlock.ACTIVE) == active) return
        lvl.setBlock(
            blockPos,
            current.setValue(WiFiExtenderBlock.ACTIVE, active),
            Block.UPDATE_CLIENTS,
        )
    }

    // ---------- client sync ----------

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

    private fun sync() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        lvl.sendBlockUpdated(blockPos, blockState, blockState, 2)
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(NBT_CHANNEL, channelId)
        tag.putInt(NBT_ENERGY, energyStorage.energyStored)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CHANNEL)) channelId = tag.getString(NBT_CHANNEL)
        if (tag.contains(NBT_ENERGY)) {
            // EnergyStorage has no setter; rebuild from saved amount by receiving.
            val stored = tag.getInt(NBT_ENERGY)
            if (stored > 0) {
                val toFill = stored - energyStorage.energyStored
                if (toFill > 0) energyStorage.receiveEnergy(toFill, false)
            }
        }
    }

    companion object {
        const val REGISTRANT_KIND: String = "extender"
        const val DEFAULT_CHANNEL: String = "default"
        const val MAX_CHANNEL_LENGTH: Int = 32
        private const val NBT_CHANNEL = "channelId"
        private const val NBT_ENERGY = "energy"

        /**
         * Snapshot every active extender on [channelId] as a list of
         * [ExtenderMesh.Extender] ready for mesh computation. "Active" means
         * the BE's current blockstate has ACTIVE=true (equivalently, energy
         * above idle cost AND channel set). Unpowered extenders are excluded.
         */
        fun activeExtendersOn(
            channelId: String,
            levelLookup: (Position) -> WiFiExtenderBlockEntity?,
        ): List<ExtenderMesh.Extender> {
            val members = ChannelRegistry.listOnChannel(channelId, REGISTRANT_KIND)
            val out = mutableListOf<ExtenderMesh.Extender>()
            for (r in members) {
                val be = levelLookup(r.location) ?: continue
                val state = be.blockState
                if (!state.getValue(WiFiExtenderBlock.ACTIVE)) continue
                out.add(ExtenderMesh.Extender(be.location, WiFiExtenderConfig.rangeBlocks))
            }
            return out
        }
    }
}
