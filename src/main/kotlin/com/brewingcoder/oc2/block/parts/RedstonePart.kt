package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartHost
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.peripheral.RedstonePeripheral

/**
 * Redstone part — reads input on its face, writes output on its face. Not
 * capability-backed (vanilla redstone isn't a NeoForge capability), so we
 * subclass [Part] directly rather than [com.brewingcoder.oc2.platform.parts.CapabilityBackedPart].
 *
 * Output level is part-owned state (persisted to NBT). On every script call
 * the wrapper goes back to the live host for input level / re-asserts output.
 */
class RedstonePart : Part {
    override val typeId: String = TYPE_ID
    override var label: String = ""
    override var channelId: String = "default"
    override val options: MutableMap<String, String> = mutableMapOf()

    /** True when the player flipped "Inverted" in the part config GUI. */
    private val inverted: Boolean get() = options["inverted"] == "true"

    private var output: Int = 0

    /** Last known host — captured on attach so the peripheral wrapper stays usable across calls. */
    private var host: PartHost? = null

    override fun onAttach(host: PartHost) {
        if (label.isEmpty()) label = host.defaultLabel(typeId)
        this.host = host
        host.writeRedstoneSignal(output)
    }

    override fun onNeighborChanged(host: PartHost) {
        this.host = host
    }

    override fun onDetach() {
        host?.writeRedstoneSignal(0)
        host = null
    }

    override fun asPeripheral(): Peripheral? {
        val h = host ?: return null
        // Snapshot inverted at wrap-time. Same `asPeripheral()` is called per
        // script lookup, so changes from the GUI propagate immediately.
        val invert = inverted
        return Wrapper(h, label, ::output, invert) { newLevel ->
            // Outgoing signal: invert if requested, then clamp to 0..15.
            val effective = (if (invert) 15 - newLevel else newLevel).coerceIn(0, 15)
            output = effective
            h.writeRedstoneSignal(effective)
        }
    }

    override fun saveNbt(out: Part.NbtWriter) {
        out.putString("label", label)
        out.putString("channelId", channelId)
        out.putInt("output", output)
        out.putString("options", com.brewingcoder.oc2.platform.parts.PartOptionsCodec.encode(options))
    }

    override fun loadNbt(input: Part.NbtReader) {
        if (input.has("label")) label = input.getString("label")
        if (input.has("channelId")) channelId = input.getString("channelId")
        if (input.has("output")) output = input.getInt("output").coerceIn(0, 15)
        if (input.has("options")) {
            options.clear()
            options.putAll(com.brewingcoder.oc2.platform.parts.PartOptionsCodec.decode(input.getString("options")))
        }
    }

    private class Wrapper(
        private val host: PartHost,
        override val name: String,
        private val outputGetter: () -> Int,
        private val invert: Boolean,
        private val outputSetter: (Int) -> Unit,
    ) : RedstonePeripheral {
        override val location: com.brewingcoder.oc2.platform.Position get() = host.location
        override fun getInput(): Int {
            val raw = host.readRedstoneSignal().coerceIn(0, 15)
            return if (invert) 15 - raw else raw
        }
        // Stored output is the EFFECTIVE signal (already inverted at write).
        // Reading it back unflips so getOutput returns the level the script set.
        override fun getOutput(): Int {
            val stored = outputGetter().coerceIn(0, 15)
            return if (invert) 15 - stored else stored
        }
        override fun setOutput(level: Int) = outputSetter(level)
    }

    companion object {
        const val TYPE_ID: String = "redstone"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = RedstonePart()
        }
    }
}
