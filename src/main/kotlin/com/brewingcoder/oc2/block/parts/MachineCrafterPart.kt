package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.MachineCrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.peripheral.RecipeIngredient
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.ContainerHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack

/**
 * Machine Crafter part — wraps an adjacent block exposing an
 * [net.neoforged.neoforge.items.IItemHandler] (Mek processors, Create kinetics,
 * vanilla furnaces, anything tagged similarly) and holds 18 [RecipeCardItem]
 * cards programmed in [RecipePattern.Mode.MACHINE]. Each card stamps an
 * "ingredients → output" intent that the part injects + extracts on demand.
 *
 * Sister to [CrafterPart] (which targets vanilla crafting tables and uses the
 * universal [net.minecraft.world.item.crafting.RecipeManager]). The split is
 * deliberate — machine recipes have no universal registry, so the card stamps
 * the output manually, and the part is just a routing surface.
 *
 * Gate criterion: the adjacent block must expose an `IItemHandler` capability
 * via NeoForge's caps system. The part still installs and the player can
 * program cards offline; it only goes silent on the script API until an item-
 * handling block is in place.
 */
class MachineCrafterPart : Part, PartWithRawNbt, HasRecipeCards {
    override val typeId: String = TYPE_ID
    override var label: String = ""
    override var channelId: String = "default"
    override val options: MutableMap<String, String> = mutableMapOf()
    override var data: String = ""

    private var host: PartHost? = null

    /** 18 card slots. Slot index = `peripheral.list()` slot id. */
    override val cards: SimpleContainer = SimpleContainer(SLOT_COUNT)

    override fun onAttach(host: PartHost) {
        if (label.isEmpty()) label = host.defaultLabel(typeId)
        this.host = host
    }

    override fun onNeighborChanged(host: PartHost) {
        this.host = host
    }

    override fun onDetach() {
        host = null
    }

    override fun asPeripheral(): Peripheral? {
        val h = host ?: return null
        // Gate on "is there any non-air neighbor?" — we used to probe the sided
        // IItemHandler capability here, but that's too strict for machines whose
        // sides are configured INPUT-only (e.g. Mek factories with default
        // config0=[1,1,1,1,1,1]): the side-keyed handler returns null even though
        // an unsided handler exists. Surface the peripheral whenever a block is
        // present and let craft-time routing throw a clear error if no handler
        // can be resolved at all.
        val id = h.adjacentBlockId()
        if (id == null || id == "minecraft:air") return null
        return Wrapper(h, this, label, data)
    }

    override fun saveNbt(out: Part.NbtWriter) {
        out.putString("label", label)
        out.putString("channelId", channelId)
        out.putString("options", com.brewingcoder.oc2.platform.parts.PartOptionsCodec.encode(options))
        if (data.isNotEmpty()) out.putString("userData", data)
    }

    override fun loadNbt(input: Part.NbtReader) {
        if (input.has("label")) label = input.getString("label")
        if (input.has("channelId")) channelId = input.getString("channelId")
        if (input.has("options")) {
            options.clear()
            options.putAll(com.brewingcoder.oc2.platform.parts.PartOptionsCodec.decode(input.getString("options")))
        }
        data = if (input.has("userData")) input.getString("userData") else ""
    }

    override fun saveRawNbt(tag: CompoundTag, registries: HolderLookup.Provider) {
        val list = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
        for (i in 0 until SLOT_COUNT) list[i] = cards.getItem(i)
        val cardsTag = CompoundTag()
        ContainerHelper.saveAllItems(cardsTag, list, registries)
        tag.put(NBT_CARDS, cardsTag)
    }

    override fun loadRawNbt(tag: CompoundTag, registries: HolderLookup.Provider) {
        cards.clearContent()
        if (!tag.contains(NBT_CARDS)) return
        val list = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(tag.getCompound(NBT_CARDS), list, registries)
        for (i in 0 until SLOT_COUNT) cards.setItem(i, list[i])
    }

    /**
     * [MachineCrafterPeripheral] view backed by the live [MachineCrafterPart.cards]
     * container. Routing is in [MachineCrafterOps] (MC-coupled); this wrapper
     * just snapshots card state and routes calls through.
     */
    private class Wrapper(
        private val host: PartHost,
        private val part: MachineCrafterPart,
        override val name: String,
        override val data: String,
    ) : MachineCrafterPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position get() = host.location
        override fun size(): Int = SLOT_COUNT

        override fun list(): List<MachineCrafterPeripheral.CardSnapshot?> =
            (0 until SLOT_COUNT).map { i ->
                val stack = part.cards.getItem(i)
                if (stack.isEmpty || stack.item !is RecipeCardItem) return@map null
                val pattern = RecipeCardItem.pattern(stack)
                if (pattern == null || pattern.isBlank) {
                    MachineCrafterPeripheral.CardSnapshot(
                        slot = i + 1,
                        output = null,
                        outputCount = 0,
                        fluidIn = null,
                        fluidInMb = 0,
                        blocking = false,
                    )
                } else if (pattern.mode != RecipePattern.Mode.MACHINE) {
                    // Table-mode card slotted in a machine crafter — surface as
                    // null so list() doesn't pretend it's runnable. craft() on
                    // this slot will throw with a clear message.
                    null
                } else {
                    val outId = if (pattern.output.isEmpty) null
                                else MachineCrafterOps.itemId(pattern.output)
                    val fluidId = if (pattern.fluidIn.isEmpty) null else pattern.fluidIn.id
                    MachineCrafterPeripheral.CardSnapshot(
                        slot = i + 1,
                        output = outId,
                        outputCount = pattern.output.count,
                        fluidIn = fluidId,
                        fluidInMb = pattern.fluidIn.mB,
                        blocking = pattern.blocking,
                        inputs = mergeIngredientIds(pattern.slots),
                    )
                }
            }

        override fun craft(
            slot: Int,
            count: Int,
            source: InventoryPeripheral,
            fluidSource: FluidPeripheral?,
        ): Int = MachineCrafterOps.craft(host, part, slot, count, source, fluidSource)

        override fun adjacentBlock(): String? = host.adjacentBlockId()
    }

    companion object {
        const val TYPE_ID: String = "machine_crafter"

        const val SLOT_COUNT: Int = 18

        private const val NBT_CARDS: String = "cards"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = MachineCrafterPart()
        }

        /** Collapse the 9-cell pattern into `(id, count)` lines, merging duplicate ids. */
        internal fun mergeIngredientIds(cells: List<ItemStack>): List<RecipeIngredient> {
            val acc = LinkedHashMap<String, Int>()
            for (cell in cells) {
                if (cell.isEmpty) continue
                val id = MachineCrafterOps.itemId(cell)
                acc[id] = (acc[id] ?: 0) + cell.count
            }
            return acc.map { (id, n) -> RecipeIngredient(id, n) }
        }
    }
}
