package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.item.RecipeCardItem
import com.brewingcoder.oc2.item.RecipePattern
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.CrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.peripheral.RecipeIngredient
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.ContainerHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack

/**
 * Crafter part — wraps an adjacent vanilla `minecraft:crafting_table` and
 * holds 18 [RecipeCardItem] cards. Each programmed card represents one recipe
 * the crafter can run on demand.
 *
 * Not capability-backed — extends [Part] directly. The cards live in an
 * internal [SimpleContainer]; the AdapterBE never sees them as an
 * IItemHandler. Scripts get a [CrafterPeripheral], not an [InventoryPeripheral]
 * — this is deliberate to keep the anti-dupe rule unambiguous (cards aren't
 * craftable inputs/outputs you can move with [InventoryPeripheral.push]).
 *
 * The "must touch a crafting_table" check is lazy — [asPeripheral] returns
 * null when the adjacent block isn't a vanilla crafting table. The part still
 * installs and the player can program cards offline; it only goes silent on
 * the script API until the table is in place.
 */
class CrafterPart : Part, PartWithRawNbt, HasRecipeCards {
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
        if (!isCraftingTable(h)) return null
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
     * [CrafterPeripheral] view backed by the live [CrafterPart.cards] container.
     * Crafting itself is in CrafterOps (MC-coupled); this wrapper just snapshots
     * card state and routes [craft] calls through.
     */
    private class Wrapper(
        private val host: PartHost,
        private val part: CrafterPart,
        override val name: String,
        override val data: String,
    ) : CrafterPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position get() = host.location
        override fun size(): Int = SLOT_COUNT

        override fun list(): List<CrafterPeripheral.CardSnapshot?> =
            (0 until SLOT_COUNT).map { i ->
                val stack = part.cards.getItem(i)
                if (stack.isEmpty || stack.item !is RecipeCardItem) return@map null
                val pattern = RecipeCardItem.pattern(stack)
                if (pattern == null || pattern.isBlank) {
                    CrafterPeripheral.CardSnapshot(slot = i + 1, output = null, outputCount = 0)
                } else {
                    // Output resolution requires the level — done in CrafterOps;
                    // surface a stable "unmatched" placeholder when not yet wired.
                    val (id, count) = CrafterOps.resolveOutput(host, pattern)
                    CrafterPeripheral.CardSnapshot(
                        slot = i + 1,
                        output = id,
                        outputCount = count,
                        inputs = MachineCrafterPart.mergeIngredientIds(pattern.slots),
                    )
                }
            }

        override fun craft(slot: Int, count: Int, source: InventoryPeripheral): Int =
            CrafterOps.craft(host, part, slot, count, source)

        override fun adjacentBlock(): String? = host.adjacentBlockId()
    }

    companion object {
        const val TYPE_ID: String = "crafter"

        /**
         * Vanilla crafting table id — accepted as a fast-path so we don't depend
         * on a tag definition for the most common case. Modded crafting tables
         * are accepted via the [CRAFTING_TABLE_TAG] check.
         */
        const val ADJACENT_BLOCK_ID: String = "minecraft:crafting_table"

        /**
         * Common (NeoForge `c:` namespace) tag for any block that acts as a
         * player crafting workstation. Mods like Crafting Station, Quark
         * workbenches etc. tag themselves here. Lets us accept any properly
         * tagged modded crafting table without a hardcoded allowlist.
         */
        const val CRAFTING_TABLE_TAG: String = "c:player_workstations/crafting_tables"

        const val SLOT_COUNT: Int = 18

        private const val NBT_CARDS: String = "cards"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = CrafterPart()
        }

        /** Whether the block adjacent to [host] qualifies as a crafting table. */
        fun isCraftingTable(host: PartHost): Boolean {
            if (host.adjacentBlockId() == ADJACENT_BLOCK_ID) return true
            return host.adjacentBlockHasTag(CRAFTING_TABLE_TAG)
        }

        fun idForBlock(host: PartHost): String? = host.adjacentBlockId()
        fun idForItem(stack: ItemStack): String =
            BuiltInRegistries.ITEM.getKey(stack.item).toString()
    }
}
