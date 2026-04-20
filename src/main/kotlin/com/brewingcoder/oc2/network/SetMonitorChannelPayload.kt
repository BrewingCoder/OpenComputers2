package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.MonitorBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: change a Monitor's wifi channel. Resolves the master of the
 * group from the clicked block, since channel lives on the master only and
 * setting it on a slave doesn't make sense (slaves don't register).
 *
 * Validation server-side:
 *   - Sender must be within 16 blocks of the clicked block (anti-grief)
 *   - Channel name length capped at 32 chars
 *   - Target must be a real MonitorBlockEntity; if it's a slave, we resolve
 *     the master's BE and apply the change there.
 */
data class SetMonitorChannelPayload(val pos: BlockPos, val channel: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetMonitorChannelPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetMonitorChannelPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "set_monitor_channel"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetMonitorChannelPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetMonitorChannelPayload::pos,
                ByteBufCodecs.STRING_UTF8, SetMonitorChannelPayload::channel,
                ::SetMonitorChannelPayload,
            )

        const val MAX_CHANNEL_LENGTH = 32
        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks

        fun handle(payload: SetMonitorChannelPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected SetMonitorChannel from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val clicked = level.getBlockEntity(payload.pos) as? MonitorBlockEntity
                    ?: return@enqueueWork
                // Resolve master — slaves can't carry the channel.
                val master = if (clicked.isMaster) clicked
                    else level.getBlockEntity(clicked.masterPos) as? MonitorBlockEntity
                        ?: return@enqueueWork
                val cleaned = payload.channel.trim().take(MAX_CHANNEL_LENGTH)
                    .ifBlank { MonitorBlockEntity.DEFAULT_CHANNEL }
                master.setChannelIdForGroup(cleaned)
            }
        }
    }
}
