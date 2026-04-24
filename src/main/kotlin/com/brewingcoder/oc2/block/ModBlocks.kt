package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredRegister
import thedarkcolour.kotlinforforge.neoforge.forge.getValue

/**
 * Block registry. We expose both the holder and the resolved block:
 *   - The holder is what other registers (Items, BlockEntities) reference.
 *   - The resolved Block is for runtime use (placement, state queries, etc).
 *
 * As of this commit, Computer is a real EntityBlock — see [ComputerBlock] —
 * which spawns a [ComputerBlockEntity] when placed. The BE registers itself
 * with [com.brewingcoder.oc2.platform.ChannelRegistry] on load.
 */
object ModBlocks {
    val REGISTRY: DeferredRegister.Blocks = DeferredRegister.createBlocks(OpenComputers2.ID)

    val COMPUTER_HOLDER: DeferredBlock<ComputerBlock> = REGISTRY.registerBlock(
        "computer",
        { props -> ComputerBlock(props) },
        BlockBehaviour.Properties.of()
            .strength(2.5f)
            .lightLevel { 6 }
    )
    val COMPUTER: ComputerBlock by COMPUTER_HOLDER

    val MONITOR_HOLDER: DeferredBlock<MonitorBlock> = REGISTRY.registerBlock(
        "monitor",
        { props -> MonitorBlock(props) },
        BlockBehaviour.Properties.of()
            .strength(2.0f)
            .lightLevel { 4 }
    )
    val MONITOR: MonitorBlock by MONITOR_HOLDER

    val ADAPTER_HOLDER: DeferredBlock<AdapterBlock> = REGISTRY.registerBlock(
        "adapter",
        { props -> AdapterBlock(props) },
        BlockBehaviour.Properties.of()
            .strength(2.0f)
    )
    val ADAPTER: AdapterBlock by ADAPTER_HOLDER

    val WIFI_EXTENDER_HOLDER: DeferredBlock<WiFiExtenderBlock> = REGISTRY.registerBlock(
        "wifi_extender",
        { props -> WiFiExtenderBlock(props) },
        BlockBehaviour.Properties.of()
            .strength(2.0f)
            .lightLevel { state -> if (state.getValue(WiFiExtenderBlock.ACTIVE)) 7 else 0 }
    )
    val WIFI_EXTENDER: WiFiExtenderBlock by WIFI_EXTENDER_HOLDER
}
