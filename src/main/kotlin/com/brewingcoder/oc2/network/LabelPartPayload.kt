package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.AdapterBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: set the label of an installed Part. The PartConfigScreen
 * sends this on Save; the AdapterBE applies the new label and the
 * [com.brewingcoder.oc2.block.parts.PartChannelRegistrant.currentPeripheral]
 * automatically reflects the new name on the next `peripheral.find` call —
 * no re-registration needed because the wrapper is rebuilt each lookup.
 *
 * Validation server-side:
 *   - Sender within 16 blocks (anti-grief)
 *   - Label cleaned: trimmed, max 32 chars, alphanumeric + underscore only
 *   - Empty after cleanup falls back to the part's existing auto-name
 *   - Target must be a real AdapterBlockEntity with a part on [face]
 */
data class LabelPartPayload(val pos: BlockPos, val face: Direction, val label: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LabelPartPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LabelPartPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "label_part"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, LabelPartPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, LabelPartPayload::pos,
                Direction.STREAM_CODEC, LabelPartPayload::face,
                ByteBufCodecs.STRING_UTF8, LabelPartPayload::label,
                ::LabelPartPayload,
            )

        const val MAX_LABEL_LENGTH = 32
        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks
        private val ALLOWED_RE = Regex("[^A-Za-z0-9_]")

        fun handle(payload: LabelPartPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected LabelPart from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val be = level.getBlockEntity(payload.pos) as? AdapterBlockEntity
                    ?: return@enqueueWork
                val cleaned = payload.label.trim().take(MAX_LABEL_LENGTH).replace(ALLOWED_RE, "_")
                be.relabelPart(payload.face, cleaned)
            }
        }
    }
}
