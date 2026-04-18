package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.parts.CapabilityBackedPart
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.parts.PartType
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral.FluidSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import net.minecraft.core.registries.BuiltInRegistries
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler

/**
 * Fluid part — wraps the adjacent block's `IFluidHandler` capability. Surface
 * mirrors [InventoryPart]; transfers go through `drain` + `fill` with simulate
 * passes for atomicity.
 */
class FluidPart : CapabilityBackedPart<IFluidHandler>(TYPE_ID, PartCapabilityKeys.FLUID) {

    override fun wrapAsPeripheral(cap: IFluidHandler): Peripheral = Wrapper(cap, label)

    private class Wrapper(
        private val handler: IFluidHandler,
        override val name: String,
    ) : FluidPeripheral {
        override fun tanks(): Int = handler.tanks

        override fun getFluid(tank: Int): FluidSnapshot? {
            val idx = tank - 1
            if (idx !in 0 until handler.tanks) return null
            return snapshot(handler.getFluidInTank(idx))
        }

        override fun list(): List<FluidSnapshot?> =
            (0 until handler.tanks).map { snapshot(handler.getFluidInTank(it)) }

        override fun push(target: FluidPeripheral, amount: Int): Int {
            val targetH = (target as? Wrapper)?.handler ?: return 0
            val drained = handler.drain(amount.coerceAtLeast(0), IFluidHandler.FluidAction.SIMULATE)
            if (drained.isEmpty) return 0
            val filled = targetH.fill(drained, IFluidHandler.FluidAction.SIMULATE)
            if (filled <= 0) return 0
            val actuallyDrained = handler.drain(drained.copyWithAmount(filled), IFluidHandler.FluidAction.EXECUTE)
            return targetH.fill(actuallyDrained, IFluidHandler.FluidAction.EXECUTE)
        }

        override fun pull(source: FluidPeripheral, amount: Int): Int {
            val srcH = (source as? Wrapper)?.handler ?: return 0
            val drained = srcH.drain(amount.coerceAtLeast(0), IFluidHandler.FluidAction.SIMULATE)
            if (drained.isEmpty) return 0
            val filled = handler.fill(drained, IFluidHandler.FluidAction.SIMULATE)
            if (filled <= 0) return 0
            val actuallyDrained = srcH.drain(drained.copyWithAmount(filled), IFluidHandler.FluidAction.EXECUTE)
            return handler.fill(actuallyDrained, IFluidHandler.FluidAction.EXECUTE)
        }

        private fun snapshot(stack: FluidStack): FluidSnapshot? {
            if (stack.isEmpty) return null
            return FluidSnapshot(BuiltInRegistries.FLUID.getKey(stack.fluid).toString(), stack.amount)
        }
    }

    companion object {
        const val TYPE_ID: String = "fluid"

        val TYPE: PartType = object : PartType {
            override val id: String = TYPE_ID
            override fun create(): Part = FluidPart()
        }
    }
}
