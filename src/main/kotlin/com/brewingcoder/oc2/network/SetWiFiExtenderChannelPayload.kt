package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.WiFiExtenderBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: change a WiFi Extender's channel. Parallels
 * [SetMonitorChannelPayload] in shape and safety checks (16-block reach limit,
 * 32-char channel cap).
 */
data class SetWiFiExtenderChannelPayload(val pos: BlockPos, val channel: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SetWiFiExtenderChannelPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SetWiFiExtenderChannelPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "set_wifi_extender_channel"),
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SetWiFiExtenderChannelPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetWiFiExtenderChannelPayload::pos,
                ByteBufCodecs.STRING_UTF8, SetWiFiExtenderChannelPayload::channel,
                ::SetWiFiExtenderChannelPayload,
            )

        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks

        fun handle(payload: SetWiFiExtenderChannelPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected SetWiFiExtenderChannel from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val be = level.getBlockEntity(payload.pos) as? WiFiExtenderBlockEntity
                    ?: return@enqueueWork
                be.setChannel(payload.channel)
            }
        }
    }
}
