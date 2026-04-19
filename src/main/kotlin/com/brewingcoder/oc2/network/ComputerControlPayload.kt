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
 * Client → server: control a Computer's power state or trigger a reset. One
 * payload covers both because they share the same target + validation; the
 * action enum picks the dispatch.
 *
 * Server side validates 16-block proximity (anti-grief — same as channel/run).
 */
data class ComputerControlPayload(val pos: BlockPos, val action: Int) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<ComputerControlPayload> = TYPE

    companion object {
        const val ACTION_POWER_ON  = 1
        const val ACTION_POWER_OFF = 2
        const val ACTION_RESET     = 3

        val TYPE: CustomPacketPayload.Type<ComputerControlPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "computer_control"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ComputerControlPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, ComputerControlPayload::pos,
                ByteBufCodecs.VAR_INT, ComputerControlPayload::action,
                ::ComputerControlPayload,
            )

        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks

        fun handle(payload: ComputerControlPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected ComputerControl from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val be = level.getBlockEntity(payload.pos) as? ComputerBlockEntity
                    ?: return@enqueueWork
                when (payload.action) {
                    ACTION_POWER_ON  -> be.setPowered(true)
                    ACTION_POWER_OFF -> be.setPowered(false)
                    ACTION_RESET     -> be.reset()
                }
            }
        }
    }
}
