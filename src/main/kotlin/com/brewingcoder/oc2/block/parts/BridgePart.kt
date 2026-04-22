package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.block.bridge.BridgeDispatcher
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity

/**
 * Universal protocol bridge — peeks at the BlockEntity adjacent to its face
 * and surfaces whatever scripting protocol that BE speaks (CC's IPeripheral,
 * ZeroCore's IComputerPort, ...) as a generic [BridgePeripheral].
 *
 * Not capability-backed — extends [Part] directly like [BlockPart] and
 * [RedstonePart]. The dispatcher decides protocol on each peripheral resolve,
 * so the same installed Bridge part adapts cleanly if the player breaks the
 * adjacent block and replaces it with something else.
 *
 * Returns a "none"-protocol stub when nothing claims the adjacent BE — scripts
 * can still call `peripheral.find("bridge")` without nil-checks; methods()
 * returns empty and call() returns null. Keeps shell-script discovery sane.
 */
class BridgePart : Part {
    override val typeId: String = TYPE_ID
    override var label: String = ""
    override var channelId: String = "default"
    override val options: MutableMap<String, String> = mutableMapOf()

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
        val be = h.adjacentBlockEntity() as? BlockEntity
        // Resolve face from PartHost.faceId — kept as a string in platform layer.
        val face = Direction.byName(h.faceId) ?: return null
        return BridgeDispatcher.discover(be, face, label, h.location)
            ?: NonePeripheral(label, beTarget(be), h.location)
    }

    /** Diagnostic identifier for the [NonePeripheral] target field — surfaces the BE class name so developers can see what protocol they need to add an adapter for. */
    private fun beTarget(be: BlockEntity?): String =
        if (be == null) "(no block entity adjacent)"
        else "${be.javaClass.name} @ ${be.blockState.block.javaClass.simpleName}"

    override fun saveNbt(out: Part.NbtWriter) {
        out.putString("label", label)
        out.putString("channelId", channelId)
        out.putString("options", com.brewingcoder.oc2.platform.parts.PartOptionsCodec.encode(options))
    }

    override fun loadNbt(input: Part.NbtReader) {
        if (input.has("label")) label = input.getString("label")
        if (input.has("channelId")) channelId = input.getString("channelId")
        if (input.has("options")) {
            options.clear()
            options.putAll(com.brewingcoder.oc2.platform.parts.PartOptionsCodec.decode(input.getString("options")))
        }
    }

    /** Stub returned when no adapter claims the adjacent BE. Lets scripts probe without nil. */
    private class NonePeripheral(override val name: String, override val target: String, override val location: com.brewingcoder.oc2.platform.Position) : BridgePeripheral {
        override val protocol: String = "none"
        override fun methods(): List<String> = emptyList()
        override fun call(method: String, args: List<Any?>): Any? = null
    }

    companion object {
        const val TYPE_ID: String = "bridge"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = BridgePart()
        }
    }
}
