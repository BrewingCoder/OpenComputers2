package com.brewingcoder.oc2.block.parts

import net.neoforged.neoforge.items.IItemHandler

/**
 * Internal SPI exposing an [IItemHandler] off any [InventoryPeripheral] impl,
 * so cross-peripheral push/pull can do an `IItemHandler`-to-`IItemHandler`
 * extract+insert without going back through the platform-pure interface (which
 * would recurse).
 *
 * Both [InventoryPart.Wrapper] (capability-backed parts) and bridge-side
 * machine peripherals (e.g. `MekanismMachineAdapter.MachinePeripheral`) implement
 * this so the chest's `push(slot, smelter)` can resolve to a raw handler when
 * the target is a Mek factory exposing items through its bridge.
 *
 * Returns null when the underlying capability has gone away (block removed,
 * chunk unloaded). Callers must treat null as "skip this transfer."
 */
internal interface ItemHandlerHost {
    val itemHandler: IItemHandler?
}
