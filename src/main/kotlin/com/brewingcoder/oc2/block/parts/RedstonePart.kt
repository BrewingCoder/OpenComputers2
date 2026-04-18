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
        return Wrapper(h, label, ::output) { newLevel ->
            output = newLevel.coerceIn(0, 15)
            h.writeRedstoneSignal(output)
        }
    }

    override fun saveNbt(out: Part.NbtWriter) {
        out.putString("label", label)
        out.putInt("output", output)
    }

    override fun loadNbt(input: Part.NbtReader) {
        if (input.has("label")) label = input.getString("label")
        if (input.has("output")) output = input.getInt("output").coerceIn(0, 15)
    }

    private class Wrapper(
        private val host: PartHost,
        override val name: String,
        private val outputGetter: () -> Int,
        private val outputSetter: (Int) -> Unit,
    ) : RedstonePeripheral {
        override fun getInput(): Int = host.readRedstoneSignal().coerceIn(0, 15)
        override fun getOutput(): Int = outputGetter()
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
