package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import com.brewingcoder.oc2.item.RecipePattern
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Client → server: ghost-fill the open Programmer's 3×3 grid from a JEI
 * "Move Items" transfer (or any future automation source). The 9 stacks map
 * row-major to ghost slots 0..8; empty stacks clear those cells.
 *
 * [output] is the manual-output ghost slot (machine mode). In TABLE mode the
 * server ignores it (the screen renders the RecipeManager preview on top
 * regardless). For non-shaped recipe types coming through the universal JEI
 * handler — smelting/blasting/Mek/Create — the OUTPUT slot view is captured
 * here and stamped into [RecipeProgrammerMenu.ghostOutput] so the player
 * doesn't need to manually re-stamp the result.
 *
 * No item ownership check — these are display-only ghosts. Save still requires
 * a programmable card in hand and writes the pattern from the menu's grid, so
 * a populated-but-not-saved card is the player's commit point.
 */
data class PopulateRecipePatternPayload(
    val containerId: Int,
    val cells: List<ItemStack>,
    val output: ItemStack = ItemStack.EMPTY,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<PopulateRecipePatternPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<PopulateRecipePatternPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "populate_recipe_pattern"))

        private val CELLS_CODEC: StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(
                ByteBufCodecs.collection({ size -> ArrayList<ItemStack>(size) })
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PopulateRecipePatternPayload> =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PopulateRecipePatternPayload::containerId,
                CELLS_CODEC, PopulateRecipePatternPayload::cells,
                ItemStack.OPTIONAL_STREAM_CODEC, PopulateRecipePatternPayload::output,
                ::PopulateRecipePatternPayload,
            )

        fun handle(payload: PopulateRecipePatternPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val menu = player.containerMenu as? RecipeProgrammerMenu ?: return@enqueueWork
                if (menu.containerId != payload.containerId) return@enqueueWork

                for (i in 0 until RecipePattern.SIZE) {
                    val src = payload.cells.getOrNull(i) ?: ItemStack.EMPTY
                    val ghost = if (src.isEmpty) ItemStack.EMPTY else src.copyWithCount(1)
                    menu.ghostGrid.setItem(i, ghost)
                }
                menu.ghostOutput.setItem(0, if (payload.output.isEmpty) ItemStack.EMPTY else payload.output.copy())
                menu.broadcastChanges()
            }
        }
    }
}
