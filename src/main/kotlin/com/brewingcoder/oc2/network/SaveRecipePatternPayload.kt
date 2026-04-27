package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.RecipeProgrammerMenu
import com.brewingcoder.oc2.item.ModDataComponents
import com.brewingcoder.oc2.item.RecipeCardItem
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
 * Client → server: trigger the Programmer GUI's Save (or Clear) action.
 *
 * Carries the open menu's [containerId] plus three pieces of side state that
 * aren't in the menu's ghost slots — [mode] (table vs machine), [blocking]
 * (per-recipe blocking flag for machine mode) and [fluidInId]/[fluidInMb]
 * (optional fluid input requirement). The server reads its own
 * [RecipeProgrammerMenu.ghostGrid] (kept in sync by vanilla slot mechanics)
 * for the input cells AND the manual-output ghost; the toggle state above
 * comes straight from this payload.
 *
 * If [clear] is true, the held stack's pattern component is removed instead
 * of a fresh save. The Save button also closes the screen client-side; Clear
 * leaves it open so the player can reprogram.
 *
 * Save semantics:
 *   - Held stack count == 1 → write the component on it in place.
 *   - Held stack count >  1 → split off one card, write the component on the
 *     split, return the remainder to the held slot.
 *   - Held stack is not a recipe card → no-op (defensive against desync).
 */
data class SaveRecipePatternPayload(
    val containerId: Int,
    val clear: Boolean = false,
    val mode: String = RecipePattern.Mode.TABLE.id,
    val blocking: Boolean = false,
    val fluidInId: String = "",
    val fluidInMb: Int = 0,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<SaveRecipePatternPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SaveRecipePatternPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "save_recipe_pattern"))

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SaveRecipePatternPayload> =
            StreamCodec.of(
                { buf, payload ->
                    ByteBufCodecs.VAR_INT.encode(buf, payload.containerId)
                    ByteBufCodecs.BOOL.encode(buf, payload.clear)
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.mode)
                    ByteBufCodecs.BOOL.encode(buf, payload.blocking)
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.fluidInId)
                    ByteBufCodecs.VAR_INT.encode(buf, payload.fluidInMb)
                },
                { buf ->
                    val containerId = ByteBufCodecs.VAR_INT.decode(buf)
                    val clear = ByteBufCodecs.BOOL.decode(buf)
                    val mode = ByteBufCodecs.STRING_UTF8.decode(buf)
                    val blocking = ByteBufCodecs.BOOL.decode(buf)
                    val fluidInId = ByteBufCodecs.STRING_UTF8.decode(buf)
                    val fluidInMb = ByteBufCodecs.VAR_INT.decode(buf)
                    SaveRecipePatternPayload(containerId, clear, mode, blocking, fluidInId, fluidInMb)
                },
            )

        fun handle(payload: SaveRecipePatternPayload, context: IPayloadContext) {
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val menu = player.containerMenu as? RecipeProgrammerMenu ?: return@enqueueWork
                if (menu.containerId != payload.containerId) return@enqueueWork
                val held = player.getItemInHand(menu.hand)
                if (held.isEmpty || held.item !is RecipeCardItem) return@enqueueWork
                val componentType = ModDataComponents.RECIPE_PATTERN.get()

                if (payload.clear) {
                    if (held.count == 1) {
                        held.remove(componentType)
                    } else {
                        val one = held.split(1)
                        one.remove(componentType)
                        giveOrCursor(player, one)
                    }
                    return@enqueueWork
                }

                val mode = RecipePattern.Mode.fromId(payload.mode)
                val fluidIn = if (payload.fluidInId.isEmpty() || payload.fluidInMb <= 0)
                    RecipePattern.FluidSpec.EMPTY
                else
                    RecipePattern.FluidSpec(payload.fluidInId, payload.fluidInMb)

                val pattern = menu.snapshotPattern(mode, payload.blocking, fluidIn)
                if (pattern.isBlank) {
                    if (held.count == 1) held.remove(componentType)
                    return@enqueueWork
                }

                if (held.count == 1) {
                    held.set(componentType, pattern)
                } else {
                    val one = held.split(1)
                    one.set(componentType, pattern)
                    giveOrCursor(player, one)
                }
            }
        }

        /** Try cursor first (UX-friendly when player saved while still holding cursor); fall back to inventory. */
        private fun giveOrCursor(player: ServerPlayer, stack: ItemStack) {
            if (player.containerMenu.carried.isEmpty) {
                player.containerMenu.carried = stack
            } else if (!player.inventory.add(stack)) {
                player.drop(stack, false)
            }
        }
    }
}
