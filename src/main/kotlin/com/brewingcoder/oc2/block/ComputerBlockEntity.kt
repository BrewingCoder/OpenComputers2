package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.ChannelRegistry
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

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
 * Lifecycle:
 *   - onLoad()      → registered with ChannelRegistry
 *   - setRemoved()  → unregistered from ChannelRegistry
 *   - tick()        → called each server tick (heartbeat / scheduler tick later)
 */
class ComputerBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.COMPUTER.get(), pos, state) {

    /** Wifi channel this Computer publishes on. Matches with adapters of the same channel. */
    var channelId: String = DEFAULT_CHANNEL

    private var tickCounter: Int = 0

    override fun onLoad() {
        super.onLoad()
        ChannelRegistry.register(this)
    }

    override fun setRemoved() {
        ChannelRegistry.unregister(this)
        super.setRemoved()
    }

    /** Called every server tick — wired up by ComputerBlock.getTicker. */
    fun tick() {
        tickCounter++
        if (tickCounter % HEARTBEAT_TICKS == 0) {
            OpenComputers2.LOGGER.debug(
                "computer @ {} alive on channel '{}' ({} ticks)",
                blockPos, channelId, tickCounter
            )
        }
    }

    /** Reassign the channel; updates the registry. */
    fun setChannel(newChannel: String) {
        if (newChannel == channelId) return
        ChannelRegistry.unregister(this)
        channelId = newChannel
        ChannelRegistry.register(this)
        setChanged()  // marks chunk dirty so NBT gets persisted
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString(NBT_CHANNEL, channelId)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        channelId = input.getStringOr(NBT_CHANNEL, DEFAULT_CHANNEL)
    }

    companion object {
        const val DEFAULT_CHANNEL = "default"
        const val HEARTBEAT_TICKS = 100  // 5s at 20 TPS
        private const val NBT_CHANNEL = "channelId"
    }
}
