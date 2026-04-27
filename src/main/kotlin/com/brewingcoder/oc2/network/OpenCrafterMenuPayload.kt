package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.AdapterBlockEntity
import com.brewingcoder.oc2.block.parts.CrafterMenu
import com.brewingcoder.oc2.block.parts.HasRecipeCards
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: open the [CrafterMenu] for the Crafter Part on
 * [pos]+[face]. Triggered by the "Recipes…" button in the Part Settings
 * screen, which is itself only shown when the part's kind is `crafter`.
 *
 * Server validates: pos is an [AdapterBlockEntity], face holds a [CrafterPart],
 * and the sender is within reach. On success, opens the menu via
 * [Player.openMenu] passing pos+face into the menu's network buffer.
 */
data class OpenCrafterMenuPayload(
    val pos: BlockPos,
    val face: Direction,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<OpenCrafterMenuPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<OpenCrafterMenuPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "open_crafter_menu"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenCrafterMenuPayload> =
            StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenCrafterMenuPayload::pos,
                Direction.STREAM_CODEC, OpenCrafterMenuPayload::face,
                ::OpenCrafterMenuPayload,
            )

        const val MAX_INTERACTION_DISTANCE_SQ = 256.0  // 16 blocks

        fun handle(payload: OpenCrafterMenuPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                if (player.blockPosition().distSqr(payload.pos) > MAX_INTERACTION_DISTANCE_SQ) return@enqueueWork
                val be = player.serverLevel().getBlockEntity(payload.pos) as? AdapterBlockEntity
                    ?: return@enqueueWork
                val part = be.partOn(payload.face) as? HasRecipeCards ?: return@enqueueWork
                player.openMenu(
                    object : MenuProvider {
                        override fun getDisplayName() =
                            net.minecraft.network.chat.Component.translatable(
                                "screen.${OpenComputers2.ID}.crafter"
                            )

                        override fun createMenu(id: Int, inv: Inventory, p: Player): AbstractContainerMenu =
                            CrafterMenu(id, inv, be, payload.face)
                    }
                ) { buf ->
                    buf.writeBlockPos(payload.pos)
                    buf.writeByte(payload.face.get3DDataValue())
                }
            }
        }
    }
}
