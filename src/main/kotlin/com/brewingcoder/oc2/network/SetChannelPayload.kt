package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: change a Computer's wifi channel.
 *
 * Validation server-side:
 *   - Sender must be within 16 blocks of the target Computer (anti-grief)
 *   - Channel name length capped at 32 chars
 *   - Target must be a real ComputerBlockEntity
 *
 * On success, [ComputerBlockEntity.setChannel] handles the
 * unregister/re-register cycle in [com.brewingcoder.oc2.platform.ChannelRegistry].
 */
data class SetChannelPayload(val pos: BlockPos, val channel: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetChannelPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetChannelPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "set_channel"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetChannelPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetChannelPayload::pos,
                ByteBufCodecs.STRING_UTF8, SetChannelPayload::channel,
                ::SetChannelPayload,
            )

        const val MAX_CHANNEL_LENGTH = 32
        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks

        fun handle(payload: SetChannelPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected SetChannel from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val be = level.getBlockEntity(payload.pos) as? ComputerBlockEntity
                    ?: return@enqueueWork
                val cleaned = payload.channel.trim().take(MAX_CHANNEL_LENGTH).ifBlank { ComputerBlockEntity.DEFAULT_CHANNEL }
                be.setChannel(cleaned)
            }
        }
    }
}
