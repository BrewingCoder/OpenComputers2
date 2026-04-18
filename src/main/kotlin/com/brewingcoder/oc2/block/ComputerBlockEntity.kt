package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.Position
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Persistent state for a single Computer block.
 *
 * v0 holds only the wifi channel ID. As R1 lands this will gain:
 *   - script bound to this computer
 *   - VM kind (lua | js)
 *   - filesystem handle
 *   - bound screen reference (if any)
 *   - power state (on/off)
 *
 * Lifecycle (server-side only — client BE never registers, see [registryShouldTrack]):
 *   - onLoad()      → registered with ChannelRegistry
 *   - setRemoved()  → unregistered from ChannelRegistry
 *   - tick()        → called each server tick (heartbeat / scheduler tick later)
 *
 * Implements [ChannelRegistrant] (Rule B in docs/11) so the registry never
 * has to know about BlockEntity.
 */
class ComputerBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.COMPUTER.get(), pos, state),
    ChannelRegistrant {

    /** Wifi channel this Computer publishes on. Matches with adapters of the same channel. */
    override var channelId: String = DEFAULT_CHANNEL
        private set

    /** Block position translated to platform-layer [Position] — exposed via [ChannelRegistrant]. */
    override val location: Position
        get() = Position(blockPos.x, blockPos.y, blockPos.z)

    private var tickCounter: Int = 0

    /** Server-side BEs are the source of truth; client BEs are visual only. */
    private val registryShouldTrack: Boolean
        get() = level?.isClientSide == false

    override fun onLoad() {
        super.onLoad()
        if (registryShouldTrack) ChannelRegistry.register(this)
    }

    override fun setRemoved() {
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        super.setRemoved()
    }

    /** Called every server tick — wired up by ComputerBlock.getTicker. */
    fun tick() {
        tickCounter++
        if (tickCounter % HEARTBEAT_TICKS == 0) {
            OpenComputers2.LOGGER.debug(
                "computer @ {} alive on channel '{}' ({} ticks)",
                blockPos, channelId, tickCounter,
            )
        }
    }

    /** Reassign the channel; updates the registry. */
    fun setChannel(newChannel: String) {
        if (newChannel == channelId) return
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        channelId = newChannel
        if (registryShouldTrack) ChannelRegistry.register(this)
        setChanged()  // marks chunk dirty so NBT gets persisted
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(NBT_CHANNEL, channelId)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CHANNEL)) {
            channelId = tag.getString(NBT_CHANNEL)
        }
    }

    companion object {
        const val DEFAULT_CHANNEL = "default"
        const val HEARTBEAT_TICKS = 100  // 5s at 20 TPS
        private const val NBT_CHANNEL = "channelId"
    }
}
