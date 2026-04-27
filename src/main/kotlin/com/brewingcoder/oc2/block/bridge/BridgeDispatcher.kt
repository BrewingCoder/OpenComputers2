package com.brewingcoder.oc2.block.bridge

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.Position
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
     * with a no-arg constructor (we read the `INSTANCE` field). A `null` modid
     * means "always load" — the adapter depends only on NeoForge / vanilla core
     * and has no soft-dep gate.
     *
     * Order matters — first-match wins. Specific adapters must appear before
     * fallbacks.
     *
     *   1. ZeroCore — covers Big/Extreme Reactors, Turbines, Energizers
     *   2. Mekanism matrix — must match before the broader machine adapter
     *   3. Mekanism processing machines — factories + single-slot bases
     *   4. NeoForge cap fallback — claims ANY BE that exposes IItemHandler
     *      (vanilla furnace, Iron Furnaces, JumboFurnace, vanilla chests, …),
     *      treating it as a standard input/output/fuel furnace with vanilla
     *      SMELTING recipes.
     *   5. (future) `cc` — CC:Tweaked IPeripheral wrapper (covers ~100+ mods)
     */
    private val candidateAdapters: List<Pair<String?, String>> = listOf(
        "zerocore" to "com.brewingcoder.oc2.block.bridge.adapters.ZeroCoreAdapter",
        "mekanism" to "com.brewingcoder.oc2.block.bridge.adapters.MekanismMatrixAdapter",
        "mekanism" to "com.brewingcoder.oc2.block.bridge.adapters.MekanismMachineAdapter",
        null to "com.brewingcoder.oc2.block.bridge.adapters.NeoForgeCapAdapter",
    )

    /** Resolved adapters, computed lazily on first dispatch. */
    private val adapters: List<ProtocolAdapter> by lazy { loadAdapters() }

    fun discover(be: BlockEntity?, face: Direction, name: String, data: String, location: Position): BridgePeripheral? {
        if (be == null) return null
        for (adapter in adapters) {
            if (adapter.canHandle(be, face)) return adapter.wrap(be, face, name, data, location)
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
            if (modId != null && !ModList.get().isLoaded(modId)) {
                OpenComputers2.LOGGER.debug("BridgeDispatcher: skipping {} (mod {} not loaded)", fqn, modId)
                continue
            }
            try {
                val cls = Class.forName(fqn)
                val instance = cls.getField("INSTANCE").get(null) as ProtocolAdapter
                out.add(instance)
                OpenComputers2.LOGGER.info("BridgeDispatcher: registered adapter {} (mod {})", instance.id, modId ?: "<core>")
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.warn("BridgeDispatcher: failed to load $fqn", t)
            }
        }
        return out
    }
}
