package com.brewingcoder.oc2.item

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack

/**
 * Recipe-card payload stored as a [DataComponentType] on a [RecipeCardItem]
 * stack. Two modes:
 *
 *  - [Mode.TABLE] — the original 3×3 vanilla-crafting-table card. [slots] is a
 *    row-major 9-cell grid (index 0 = top-left, 4 = center, 8 = bottom-right).
 *    [output] / [fluidIn] / [blocking] are unused; output is auto-resolved at
 *    craft-time via [net.minecraft.world.item.crafting.RecipeManager].
 *  - [Mode.MACHINE] — flat ingredient list for a Machine Crafter Part. The 9
 *    cells are still used (reusing the same UI grid) but treated as an
 *    unordered ingredient list. [output] is **manually stamped** by the player
 *    (no universal RecipeManager for modded machines). [fluidIn] is an optional
 *    fluid input (id + mB). [blocking] toggles per-recipe blocking mode —
 *    matches AE2/RS/ID semantics where blocking waits for prior craft to drain
 *    before injecting next.
 *
 * Old (R1) cards on disk are list-shaped (`[ItemStack, ...]`); new cards are
 * record-shaped. The disk codec falls back to the legacy reader so existing
 * worlds keep their cards.
 */
@JvmRecord
data class RecipePattern(
    val slots: List<ItemStack>,
    val mode: Mode = Mode.TABLE,
    val output: ItemStack = ItemStack.EMPTY,
    val fluidIn: FluidSpec = FluidSpec.EMPTY,
    val blocking: Boolean = false,
) {

    init {
        require(slots.size == SIZE) { "RecipePattern is fixed ${SIZE}-cell (got ${slots.size})" }
    }

    /** True when every slot is empty AND the manual output is empty — the "blank card" state. */
    val isBlank: Boolean get() = slots.all { it.isEmpty } && output.isEmpty && fluidIn.isEmpty

    /** Snapshot for diffing — the per-slot count is irrelevant to recipe matching, only id+components matter. */
    fun copy(): RecipePattern = RecipePattern(
        slots = slots.map { it.copy() },
        mode = mode,
        output = output.copy(),
        fluidIn = fluidIn.copy(),
        blocking = blocking,
    )

    /**
     * Recipe-card flavor. Stored in the [RecipePattern] payload; switches the
     * Programmer GUI layout AND determines which Crafter Part variants accept
     * the card (table cards → vanilla CrafterPart; machine cards → MachineCrafterPart).
     */
    enum class Mode(val id: String) {
        TABLE("table"),
        MACHINE("machine");

        companion object {
            fun fromId(id: String): Mode = entries.find { it.id.equals(id, ignoreCase = true) } ?: TABLE
            val CODEC: Codec<Mode> = Codec.STRING.xmap(::fromId, Mode::id)
            val STREAM_CODEC: StreamCodec<ByteBuf, Mode> =
                ByteBufCodecs.STRING_UTF8.map(::fromId, Mode::id)
        }
    }

    /**
     * Optional fluid-input requirement for a machine recipe. Stored as id + mB
     * rather than a full [net.neoforged.neoforge.fluids.FluidStack] — the card
     * only needs "I want fluid X at mB Y", not the runtime FluidStack's NBT
     * components which only matter at the live tank end.
     */
    @JvmRecord
    data class FluidSpec(val id: String, val mB: Int) {
        val isEmpty: Boolean get() = id.isEmpty() || mB <= 0

        @Suppress("RedundantOverride")
        fun copy(): FluidSpec = FluidSpec(id, mB)

        companion object {
            val EMPTY: FluidSpec = FluidSpec("", 0)

            val CODEC: Codec<FluidSpec> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.optionalFieldOf("id", "").forGetter(FluidSpec::id),
                    Codec.INT.optionalFieldOf("mB", 0).forGetter(FluidSpec::mB),
                ).apply(i, ::FluidSpec)
            }

            val STREAM_CODEC: StreamCodec<ByteBuf, FluidSpec> = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, FluidSpec::id,
                ByteBufCodecs.VAR_INT, FluidSpec::mB,
                ::FluidSpec,
            )
        }
    }

    companion object {
        const val SIZE: Int = 9
        const val WIDTH: Int = 3
        const val HEIGHT: Int = 3

        val EMPTY: RecipePattern = RecipePattern(List(SIZE) { ItemStack.EMPTY })

        private fun pad(list: List<ItemStack>): List<ItemStack> = when {
            list.size == SIZE -> list
            list.size < SIZE -> list + List(SIZE - list.size) { ItemStack.EMPTY }
            else -> list.take(SIZE)
        }

        /** New record-shaped codec — used by all writes; reads when the data is record-shaped. */
        private val RECORD_CODEC: Codec<RecipePattern> = RecordCodecBuilder.create { i ->
            i.group(
                ItemStack.OPTIONAL_CODEC.listOf().fieldOf("slots")
                    .forGetter(RecipePattern::slots),
                Mode.CODEC.optionalFieldOf("mode", Mode.TABLE)
                    .forGetter(RecipePattern::mode),
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("output", ItemStack.EMPTY)
                    .forGetter(RecipePattern::output),
                FluidSpec.CODEC.optionalFieldOf("fluidIn", FluidSpec.EMPTY)
                    .forGetter(RecipePattern::fluidIn),
                Codec.BOOL.optionalFieldOf("blocking", false)
                    .forGetter(RecipePattern::blocking),
            ).apply(i, ::RecipePattern)
        }.xmap(
            { p -> p.copy(slots = pad(p.slots).map { it.copy() }) },
            { it },
        )

        /** Legacy R1 codec — flat list of ItemStacks. Read-only fallback for old saves. */
        private val LEGACY_CODEC: Codec<RecipePattern> =
            ItemStack.OPTIONAL_CODEC.listOf().xmap(
                { list -> RecipePattern(pad(list).map { it.copy() }) },
                { it.slots },
            )

        val CODEC: Codec<RecipePattern> =
            Codec.either(RECORD_CODEC, LEGACY_CODEC).xmap(
                { either -> either.map({ it }, { it }) },
                { com.mojang.datafixers.util.Either.left(it) },
            )

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RecipePattern> =
            object : StreamCodec<RegistryFriendlyByteBuf, RecipePattern> {
                override fun decode(buf: RegistryFriendlyByteBuf): RecipePattern {
                    val rawSlots = ItemStack.OPTIONAL_STREAM_CODEC
                        .apply(ByteBufCodecs.collection<RegistryFriendlyByteBuf, ItemStack, MutableList<ItemStack>>({ ArrayList(it) }))
                        .decode(buf)
                    val mode = Mode.STREAM_CODEC.decode(buf)
                    val output = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)
                    val fluidIn = FluidSpec.STREAM_CODEC.decode(buf)
                    val blocking = ByteBufCodecs.BOOL.decode(buf)
                    return RecipePattern(
                        slots = pad(rawSlots).map { it.copy() },
                        mode = mode,
                        output = output,
                        fluidIn = fluidIn,
                        blocking = blocking,
                    )
                }

                override fun encode(buf: RegistryFriendlyByteBuf, value: RecipePattern) {
                    ItemStack.OPTIONAL_STREAM_CODEC
                        .apply(ByteBufCodecs.collection<RegistryFriendlyByteBuf, ItemStack, MutableList<ItemStack>>({ ArrayList(it) }))
                        .encode(buf, ArrayList(value.slots))
                    Mode.STREAM_CODEC.encode(buf, value.mode)
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, value.output)
                    FluidSpec.STREAM_CODEC.encode(buf, value.fluidIn)
                    ByteBufCodecs.BOOL.encode(buf, value.blocking)
                }
            }
    }
}
