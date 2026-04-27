package com.brewingcoder.oc2.block.bridge.adapters

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.bridge.ProtocolAdapter
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity
import java.lang.reflect.Method

/**
 * Mekanism Induction Matrix adapter. Surfaces matrix stats by walking from the
 * port BE through `getMultiblock()` to the actual `MatrixMultiblockData` —
 * Mekanism stores all the energy/cell/provider state on the multiblock data,
 * not the port BE itself, so the BE has no useful no-arg getters.
 *
 * Reflection-based so we don't compile against Mekanism. Loaded by
 * [com.brewingcoder.oc2.block.bridge.BridgeDispatcher] only after `mekanism`
 * is in [net.neoforged.fml.ModList].
 *
 * **Energy unit:** all energy/rate methods return **Forge Energy (FE)** — the
 * unit Mekanism's GUI shows by default in modern versions. Mekanism's API
 * returns raw Joules; we multiply by [J_TO_FE] (0.4 — the inverse of
 * Mekanism's default `general.ENERGY_CONVERSION_FROM_FE = 2.5`). For raw
 * Joule values use the `*J` variants. If a player has changed Mekanism's
 * energy ratio config the conversion will be off — this is a fixed
 * compromise for not compiling against Mekanism.
 *
 * Surface (CC:T-style names mapped onto MatrixMultiblockData getters):
 *   getEnergy            → MatrixMultiblockData.getEnergy()       [FE]
 *   getMaxEnergy         → MatrixMultiblockData.getStorageCap()   [FE]
 *   getEnergyNeeded      → derived (max - stored)                  [FE]
 *   getEnergyFilledPercentage → derived (stored / max)  (0..1)
 *   getTransferCap       → MatrixMultiblockData.getTransferCap()  [FE/t]
 *   getLastInput         → MatrixMultiblockData.getLastInput()    [FE/t]
 *   getLastOutput        → MatrixMultiblockData.getLastOutput()   [FE/t]
 *   getEnergyJ / getMaxEnergyJ / getTransferCapJ /
 *     getLastInputJ / getLastOutputJ                              [raw J]
 *   getInstalledCells    → MatrixMultiblockData.getCellCount()
 *   getInstalledProviders→ MatrixMultiblockData.getProviderCount()
 *   isFormed             → MultiblockData.isFormed()
 *
 * Unformed multiblock returns null/0 from all energy getters — script-side
 * `or 0` handles it.
 */
object MekanismMatrixAdapter : ProtocolAdapter {
    override val id: String = "mekanism-matrix"

    /** Joules → FE. Inverse of Mekanism's default `ENERGY_CONVERSION_FROM_FE = 2.5`. */
    private const val J_TO_FE: Double = 0.4

    private const val PORT_FQN = "mekanism.common.tile.multiblock.TileEntityInductionPort"

    private val portClass: Class<*>? by lazy { tryLoad(PORT_FQN) }

    /** TileEntityMultiblock<T>.getMultiblock(): T — present on every Mekanism multiblock BE. */
    private val getMultiblockMethod: Method? by lazy {
        portClass?.let { findNoArgMethod(it, "getMultiblock") }
    }

    /**
     * Mapping from script-facing name to (target = "be" or "data", method name on that target).
     * "be"   → invoke on the port BE itself
     * "data" → invoke on the result of getMultiblock()
     * Derived/converted methods aren't in this map — they're computed from these.
     */
    private val methodMap: Map<String, Pair<String, String>> = mapOf(
        "getInstalledCells"     to ("data" to "getCellCount"),
        "getInstalledProviders" to ("data" to "getProviderCount"),
        "isFormed"              to ("data" to "isFormed"),
    )

    /** Energy methods: name → underlying MatrixMultiblockData getter (returns Joules). */
    private val energyMap: Map<String, String> = mapOf(
        "getEnergy"      to "getEnergy",
        "getMaxEnergy"   to "getStorageCap",
        "getTransferCap" to "getTransferCap",
        "getLastInput"   to "getLastInput",
        "getLastOutput"  to "getLastOutput",
    )

    /** FE-converted energy method names (returned to scripts). */
    private val energyFE: Set<String> = energyMap.keys

    /** Raw-Joule alias for each energy method, e.g. getEnergy → getEnergyJ. */
    private val energyJ: Set<String> = energyMap.keys.map { "${it}J" }.toSet()

    private val derivedMethods: List<String> = listOf(
        "getEnergyNeeded",
        "getEnergyFilledPercentage",
    )

    /**
     * Walks the class hierarchy + interface tree looking for a public no-arg method.
     * Necessary because Mekanism's `getMultiblock()` is a `default` method on the
     * `IMultiblock<T>` interface — `declaredMethods` won't surface it on subclasses,
     * but `getMethod` (which traverses interfaces) will.
     */
    private fun findNoArgMethod(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == 0) {
                    return try { m.isAccessible = true; m } catch (_: Throwable) { m }
                }
            }
            c = c.superclass
        }
        return try {
            cls.getMethod(name).also { it.isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    override fun canHandle(be: BlockEntity, face: Direction): Boolean {
        val cls = portClass ?: return false
        return cls.isInstance(be)
    }

    override fun wrap(be: BlockEntity, face: Direction, name: String, data: String, location: Position): BridgePeripheral? {
        val cls = portClass ?: return null
        if (!cls.isInstance(be)) return null
        return MatrixPeripheral(name, be, location, data)
    }

    private fun tryLoad(fqn: String): Class<*>? = try {
        Class.forName(fqn)
    } catch (t: Throwable) {
        OpenComputers2.LOGGER.debug("MekanismMatrixAdapter: cannot resolve $fqn", t)
        null
    }

    /** Per-instance method cache, keyed by target class+name. Built lazily on first call. */
    private class MethodCache {
        private val cache = HashMap<String, Method?>()

        fun get(cls: Class<*>, name: String): Method? {
            val key = "${cls.name}#$name"
            return cache.getOrPut(key) { findNoArgMethod(cls, name) }
        }
    }

    private class MatrixPeripheral(
        override val name: String,
        private val be: BlockEntity,
        override val location: Position,
        override val data: String,
    ) : BridgePeripheral {
        override val protocol: String = "mekanism-matrix"
        override val target: String = be.javaClass.name

        private val cache = MethodCache()

        override fun methods(): List<String> =
            energyFE.toList() + energyJ.toList() + methodMap.keys.toList() + derivedMethods + listOf("getEnergyNeededJ")

        override fun call(method: String, args: List<Any?>): Any? {
            // Energy methods (FE)
            if (method in energyFE) {
                val raw = (rawCall("data", energyMap[method]!!) as? Number)?.toDouble() ?: return null
                return raw * J_TO_FE
            }
            // Energy methods (raw J): strip trailing 'J', look up underlying getter
            if (method in energyJ) {
                val feName = method.dropLast(1)
                return normalize(rawCall("data", energyMap[feName]!!))
            }
            return when (method) {
                "getEnergyNeeded" -> {
                    val stored = (rawCall("data", "getEnergy") as? Number)?.toDouble() ?: return null
                    val cap = (rawCall("data", "getStorageCap") as? Number)?.toDouble() ?: return null
                    (cap - stored).coerceAtLeast(0.0) * J_TO_FE
                }
                "getEnergyNeededJ" -> {
                    val stored = (rawCall("data", "getEnergy") as? Number)?.toDouble() ?: return null
                    val cap = (rawCall("data", "getStorageCap") as? Number)?.toDouble() ?: return null
                    (cap - stored).coerceAtLeast(0.0)
                }
                "getEnergyFilledPercentage" -> {
                    val stored = (rawCall("data", "getEnergy") as? Number)?.toDouble() ?: return null
                    val cap = (rawCall("data", "getStorageCap") as? Number)?.toDouble() ?: return null
                    if (cap > 0.0) stored / cap else 0.0
                }
                else -> {
                    val (tgt, m) = methodMap[method] ?: return null
                    normalize(rawCall(tgt, m))
                }
            }
        }

        /** Resolve [target] ("be" | "data") → object, then invoke the no-arg method. */
        private fun rawCall(target: String, methodName: String): Any? {
            val receiver: Any = when (target) {
                "be" -> be
                "data" -> {
                    val gm = getMultiblockMethod ?: return null
                    try {
                        gm.invoke(be) ?: return null
                    } catch (t: Throwable) {
                        OpenComputers2.LOGGER.debug("MekanismMatrixAdapter: getMultiblock() failed", t)
                        return null
                    }
                }
                else -> return null
            }

            val m = cache.get(receiver.javaClass, methodName) ?: return null
            return try {
                m.invoke(receiver)
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.debug("MekanismMatrixAdapter: {}#{} failed", receiver.javaClass.simpleName, methodName, t)
                null
            }
        }

        /**
         * Normalize Mekanism return values. Cobalt numbers are 64-bit float
         * (precise to 2^53 ≈ 9 PJ), which covers any realistic matrix value.
         */
        private fun normalize(value: Any?): Any? = when (value) {
            null -> null
            is Long -> value.toDouble()
            is Int, is Short, is Byte -> (value as Number).toDouble()
            is Float, is Double -> value
            is Boolean -> value
            is String -> value
            else -> value.toString()
        }
    }
}
