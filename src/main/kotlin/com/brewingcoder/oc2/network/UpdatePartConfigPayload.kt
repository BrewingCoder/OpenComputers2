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
 * Client → server: PartConfigScreen Save. Carries both per-part and per-adapter
 * fields in one round trip:
 *   - [label]   — per-part (the clicked face's part)
 *   - [channel] — per-adapter (shared by every part on the adapter)
 *
 * Carried as one packet because the GUI saves both atomically. Server splits
 * the dispatch: relabel the part, then set the adapter channel.
 *
 * Validation server-side:
 *   - Sender within 16 blocks (anti-grief)
 *   - Label cleaned (alphanumeric + underscore, ≤32 chars). Empty → auto-name.
 *   - Channel cleaned (≤32 chars). Empty → "default".
 *   - Target must be a real AdapterBlockEntity with a part on [face].
 */
data class UpdatePartConfigPayload(
    val pos: BlockPos,
    val face: Direction,
    val label: String,
    val channel: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<UpdatePartConfigPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<UpdatePartConfigPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "update_part_config"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, UpdatePartConfigPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, UpdatePartConfigPayload::pos,
                Direction.STREAM_CODEC, UpdatePartConfigPayload::face,
                ByteBufCodecs.STRING_UTF8, UpdatePartConfigPayload::label,
                ByteBufCodecs.STRING_UTF8, UpdatePartConfigPayload::channel,
                ::UpdatePartConfigPayload,
            )

        const val MAX_LABEL_LENGTH = 32
        const val MAX_CHANNEL_LENGTH = 32
        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks
        private val ALLOWED_RE = Regex("[^A-Za-z0-9_]")

        fun handle(payload: UpdatePartConfigPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.serverLevel()
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) {
                    OpenComputers2.LOGGER.warn(
                        "rejected UpdatePartConfig from {} — too far from {}",
                        player.name.string, payload.pos,
                    )
                    return@enqueueWork
                }
                val be = level.getBlockEntity(payload.pos) as? AdapterBlockEntity
                    ?: return@enqueueWork
                val cleanedLabel = payload.label.trim().take(MAX_LABEL_LENGTH).replace(ALLOWED_RE, "_")
                val cleanedChannel = payload.channel.trim().take(MAX_CHANNEL_LENGTH).replace(ALLOWED_RE, "_")
                    .ifBlank { AdapterBlockEntity.DEFAULT_CHANNEL }
                be.relabelPart(payload.face, cleanedLabel)
                be.setPartChannel(payload.face, cleanedChannel)
            }
        }
    }
}
