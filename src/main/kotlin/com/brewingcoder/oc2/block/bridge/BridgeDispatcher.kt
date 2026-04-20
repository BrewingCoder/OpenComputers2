package com.brewingcoder.oc2.block.bridge

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.fml.ModList

/**
 * Routes `BridgePart.asPeripheral()` calls through registered [ProtocolAdapter]s.
 *
 * Adapter loading is **soft-dep aware**: each entry in [candidateAdapters] pairs
 * a target mod id with the FQN of an adapter class. The class is only loaded
 * via `Class.forName` AFTER [ModList.isLoaded] confirms the target is present —
 * until then the JVM never resolves the adapter's imports, so missing-mod
 * `NoClassDefFoundError` can't crash us. See [BRIDGE-CLASS-ISOLATION] below.
 *
 * Order matters — first match wins. Higher-fidelity / more-specific adapters
 * should appear before fallbacks.
 */
object BridgeDispatcher {

    /**
     * (target mod id, adapter class FQN). Adapter classes MUST be Kotlin objects
     * with a no-arg constructor (we read the `INSTANCE` field).
     *
     * Order:
     *   1. ZeroCore — covers Big/Extreme Reactors, Turbines, Energizers
     *   2. (future) `cc` — CC:Tweaked IPeripheral wrapper (covers ~100+ mods)
     *   3. (future) `caps` — NeoForge capability fallback
     */
    private val candidateAdapters: List<Pair<String, String>> = listOf(
        "zerocore" to "com.brewingcoder.oc2.block.bridge.adapters.ZeroCoreAdapter",
    )

    /** Resolved adapters, computed lazily on first dispatch. */
    private val adapters: List<ProtocolAdapter> by lazy { loadAdapters() }

    fun discover(be: BlockEntity?, face: Direction, name: String): BridgePeripheral? {
        if (be == null) return null
        for (adapter in adapters) {
            if (adapter.canHandle(be, face)) return adapter.wrap(be, face, name)
        }
        // Quiet by default — peripheral.find can run every tick. Devs use BridgePart's
        // NonePeripheral.target to see what BE is adjacent without log spam.
        OpenComputers2.LOGGER.debug("BridgeDispatcher: no adapter for BE={} face={}", be.javaClass.name, face)
        return null
    }

    /** For diagnostic/UI display — which protocols are live in this install. */
    fun activeProtocols(): List<String> = adapters.map { it.id }

    /**
     * [BRIDGE-CLASS-ISOLATION]
     *
     * The JVM resolves all class references when a class is loaded. If we
     * directly imported `ZeroCoreAdapter` here and ZeroCore wasn't installed,
     * loading THIS class (BridgeDispatcher) would NoClassDefFoundError on
     * `it.zerono.mods.zerocore.lib.compat.computer.IComputerPort`.
     *
     * So: gate by ModList first, then `Class.forName` only after the gate
     * passes. Once loaded, the adapter is a normal object — no per-call
     * reflection cost.
     */
    private fun loadAdapters(): List<ProtocolAdapter> {
        val out = mutableListOf<ProtocolAdapter>()
        for ((modId, fqn) in candidateAdapters) {
            if (!ModList.get().isLoaded(modId)) {
                OpenComputers2.LOGGER.debug("BridgeDispatcher: skipping {} (mod {} not loaded)", fqn, modId)
                continue
            }
            try {
                val cls = Class.forName(fqn)
                val instance = cls.getField("INSTANCE").get(null) as ProtocolAdapter
                out.add(instance)
                OpenComputers2.LOGGER.info("BridgeDispatcher: registered adapter {} (mod {})", instance.id, modId)
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.warn("BridgeDispatcher: failed to load $fqn", t)
            }
        }
        return out
    }
}
