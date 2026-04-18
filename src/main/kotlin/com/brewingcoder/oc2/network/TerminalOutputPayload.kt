package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.client.TerminalOutputDispatcher
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Server → client: shell output from a Computer's command execution.
 *
 * [clearFirst] = true means wipe the terminal buffer before appending [lines]
 * (set by the `clear` command). Multiple payloads can target the same
 * computer — they accumulate in the open Screen instance via
 * [TerminalOutputDispatcher].
 */
data class TerminalOutputPayload(
    val pos: BlockPos,
    val lines: List<String>,
    val clearFirst: Boolean,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<TerminalOutputPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TerminalOutputPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "terminal_output"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TerminalOutputPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, TerminalOutputPayload::pos,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TerminalOutputPayload::lines,
                ByteBufCodecs.BOOL, TerminalOutputPayload::clearFirst,
                ::TerminalOutputPayload,
            )

        fun handle(payload: TerminalOutputPayload, context: IPayloadContext) {
            context.enqueueWork {
                TerminalOutputDispatcher.dispatch(payload)
            }
        }
    }
}
