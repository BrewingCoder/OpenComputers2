package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.CapabilityBackedPart
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.EnergyPeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import net.neoforged.neoforge.energy.IEnergyStorage

/**
 * Energy part — wraps the adjacent block's `IEnergyStorage` capability. FE
 * transfer in either direction; both sides must agree (extract/receive flags).
 */
class EnergyPart : CapabilityBackedPart<IEnergyStorage>(TYPE_ID, PartCapabilityKeys.ENERGY) {

    override fun wrapAsPeripheral(cap: IEnergyStorage): Peripheral = Wrapper(cap, label)

    private class Wrapper(
        private val storage: IEnergyStorage,
        override val name: String,
    ) : EnergyPeripheral {
        override fun stored(): Int = storage.energyStored
        override fun capacity(): Int = storage.maxEnergyStored

        override fun pull(source: EnergyPeripheral, amount: Int): Int {
            val srcStorage = (source as? Wrapper)?.storage ?: return 0
            // Two-phase: simulate to find the real limit, then commit on both sides.
            val canExtract = srcStorage.extractEnergy(amount.coerceAtLeast(0), /* simulate = */ true)
            val canReceive = storage.receiveEnergy(canExtract, /* simulate = */ true)
            if (canReceive <= 0) return 0
            srcStorage.extractEnergy(canReceive, /* simulate = */ false)
            return storage.receiveEnergy(canReceive, /* simulate = */ false)
        }

        override fun push(target: EnergyPeripheral, amount: Int): Int =
            (target as? Wrapper)?.let { it.pull(this, amount) } ?: 0
    }

    companion object {
        const val TYPE_ID: String = "energy"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = EnergyPart()
        }
    }
}
