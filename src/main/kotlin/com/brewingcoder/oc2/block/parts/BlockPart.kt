package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.BlockPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral

/**
 * Block reader/harvester part. Not capability-backed (we're reading raw block
 * state, not a NeoForge capability), so we subclass [Part] directly like
 * [RedstonePart].
 *
 * Reads happen via [PartHost.readAdjacentBlock]; harvest via
 * [PartHost.harvestAdjacentBlock]. Both delegate to the BE which handles
 * server-thread marshaling.
 */
class BlockPart : Part {
    override val typeId: String = TYPE_ID
    override var label: String = ""
    override var channelId: String = "default"
    override val options: MutableMap<String, String> = mutableMapOf()
    override var data: String = ""

    /** Last known host — captured on attach so the wrapper stays usable across calls. */
    private var host: PartHost? = null

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
        return Wrapper(h, label, data)
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

    private class Wrapper(
        private val host: PartHost,
        override val name: String,
        override val data: String,
    ) : BlockPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position get() = host.location
        override fun read(): BlockPeripheral.BlockReadout? = host.readAdjacentBlock()
        override fun harvest(target: InventoryPeripheral?): List<InventoryPeripheral.ItemSnapshot> =
            host.harvestAdjacentBlock(target)
    }

    companion object {
        const val TYPE_ID: String = "block"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = BlockPart()
        }
    }
}
