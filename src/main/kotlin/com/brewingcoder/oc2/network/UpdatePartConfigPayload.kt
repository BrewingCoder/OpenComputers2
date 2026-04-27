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
    val accessSide: String,  // "" / "auto" = default; "north" etc. = override
    val options: String,     // PartOptionsCodec-encoded "k=v;k=v"
    val data: String,        // free-form user text — multi-line, no validation
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<UpdatePartConfigPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<UpdatePartConfigPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "update_part_config"))

        // ByteBufCodecs.STRING_UTF8 is bounded — its default cap is 32767 chars.
        // For the multi-line `data` field we want a slightly more generous,
        // explicit cap (no surprise truncation if a player pastes a big block).
        private val DATA_STRING_CODEC: StreamCodec<io.netty.buffer.ByteBuf, String> =
            ByteBufCodecs.stringUtf8(MAX_DATA_LENGTH)

        // StreamCodec.composite caps at 6 fields — dropping into the 7-field form
        // means going through the explicit composite helper. Manual encode/decode
        // keeps it simple here and avoids the overload lookup.
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, UpdatePartConfigPayload> =
            object : StreamCodec<RegistryFriendlyByteBuf, UpdatePartConfigPayload> {
                override fun decode(buf: RegistryFriendlyByteBuf): UpdatePartConfigPayload =
                    UpdatePartConfigPayload(
                        pos = BlockPos.STREAM_CODEC.decode(buf),
                        face = Direction.STREAM_CODEC.decode(buf),
                        label = ByteBufCodecs.STRING_UTF8.decode(buf),
                        channel = ByteBufCodecs.STRING_UTF8.decode(buf),
                        accessSide = ByteBufCodecs.STRING_UTF8.decode(buf),
                        options = ByteBufCodecs.STRING_UTF8.decode(buf),
                        data = DATA_STRING_CODEC.decode(buf),
                    )

                override fun encode(buf: RegistryFriendlyByteBuf, p: UpdatePartConfigPayload) {
                    BlockPos.STREAM_CODEC.encode(buf, p.pos)
                    Direction.STREAM_CODEC.encode(buf, p.face)
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.label)
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.channel)
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.accessSide)
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.options)
                    DATA_STRING_CODEC.encode(buf, p.data)
                }
            }

        const val MAX_LABEL_LENGTH = 32
        const val MAX_CHANNEL_LENGTH = 32
        const val MAX_DATA_LENGTH = 8192    // server-side cap on the free-form data blob
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
                // accessSide: "auto" or "" → default (empty stored). Otherwise
                // a face name; validated by Direction.byName at apply time.
                val cleanedSide = payload.accessSide.trim().lowercase().let {
                    if (it == "auto" || it.isBlank()) "" else it
                }
                be.relabelPart(payload.face, cleanedLabel)
                be.setPartChannel(payload.face, cleanedChannel)
                be.setPartAccessSide(payload.face, cleanedSide)
                be.setPartOptions(payload.face,
                    com.brewingcoder.oc2.platform.parts.PartOptionsCodec.decode(payload.options))
                // `data` is intentionally NOT scrubbed — it's free-form text by
                // contract. Only the length cap (enforced by the stream codec)
                // protects against abuse.
                be.setPartData(payload.face, payload.data.take(MAX_DATA_LENGTH))
            }
        }
    }
}
