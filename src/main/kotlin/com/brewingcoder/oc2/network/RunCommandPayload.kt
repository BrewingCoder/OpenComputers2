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
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: run a single shell command on the Computer at [pos].
 *
 * Server-side validation:
 *   - Sender must be within 16 blocks (anti-grief — no remote shell across the world)
 *   - Command length capped at [MAX_COMMAND_LENGTH] (anti-spam)
 *   - Target must be a real ComputerBlockEntity
 *
 * On success, dispatches the command to the BE's [ComputerBlockEntity.executeShellCommand]
 * and sends the result back to the originating player as a [TerminalOutputPayload].
 */
data class RunCommandPayload(val pos: BlockPos, val command: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<RunCommandPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RunCommandPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "run_command"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RunCommandPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, RunCommandPayload::pos,
                ByteBufCodecs.STRING_UTF8, RunCommandPayload::command,
                ::RunCommandPayload,
            )

        const val MAX_COMMAND_LENGTH = 512
        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks

        fun handle(payload: RunCommandPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected RunCommand from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val be = player.serverLevel().getBlockEntity(payload.pos) as? ComputerBlockEntity
                    ?: return@enqueueWork
                val cmd = payload.command.take(MAX_COMMAND_LENGTH)
                val result = be.executeShellCommand(cmd, originator = player.uuid)
                PacketDistributor.sendToPlayer(
                    player,
                    TerminalOutputPayload(payload.pos, result.lines, result.clearScreen),
                )
            }
        }
    }
}
