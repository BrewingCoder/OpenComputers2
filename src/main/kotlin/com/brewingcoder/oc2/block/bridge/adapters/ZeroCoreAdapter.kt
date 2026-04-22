package com.brewingcoder.oc2.block.bridge.adapters

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.bridge.ProtocolAdapter
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import net.minecraft.core.Direction
import net.minecraft.world.level.block.entity.BlockEntity
import java.lang.reflect.Method

/**
 * ZeroCore (`it.zerono.mods.zerocore.lib.compat.computer.IComputerPort`) adapter
 * — covers Big/Extreme Reactors, Turbines, Energizers, and any other mod built
 * on ZeroCore's computer-port abstraction.
 *
 * **Implemented via reflection** so OC2 doesn't pick up a compile-time dep on
 * ZeroCore. The class is only loaded by [com.brewingcoder.oc2.block.bridge.BridgeDispatcher]
 * AFTER `ModList.isLoaded("zerocore")` passes, so the reflective lookups always
 * find their targets in practice.
 *
 * Surface (per `ReactorComputerPeripheral.populateMethods`): ~40 methods
 * including `getActive`, `setActive`, `getEnergyStored`, `getEnergyCapacity`,
 * `getControlRodLevel`, `setControlRodLevel`, `getFuelStats`, `getNumberOfControlRods`,
 * `getColdFluid`, `getHotFluid`, etc.
 */
object ZeroCoreAdapter : ProtocolAdapter {
    override val id: String = "zerocore"

    private const val IFACE_FQN = "it.zerono.mods.zerocore.lib.compat.computer.IComputerPort"
    private const val PERIPHERAL_FQN = "it.zerono.mods.zerocore.lib.compat.computer.ComputerPeripheral"
    private const val CONNECTOR_FQN = "it.zerono.mods.zerocore.lib.compat.computer.Connector"

    private val ifaceClass: Class<*>? by lazy { tryLoad(IFACE_FQN) }
    private val peripheralClass: Class<*>? by lazy { tryLoad(PERIPHERAL_FQN) }
    private val connectorClass: Class<*>? by lazy { tryLoad(CONNECTOR_FQN) }

    /** `IComputerPort.getConnector(Direction)` — returns a `Connector<? extends ComputerPeripheral<?>>`. */
    private val getConnectorMethod: Method? by lazy {
        ifaceClass?.runCatching { getMethod("getConnector", Direction::class.java) }?.getOrNull()
    }

    /** `Connector.getPeripheral()` — returns the wrapped `ComputerPeripheral`. */
    private val connectorGetPeripheralMethod: Method? by lazy {
        connectorClass?.runCatching { getMethod("getPeripheral") }?.getOrNull()
    }

    /** `ComputerPeripheral.invoke(String, Object[])` — public method-name dispatch. */
    private val invokeMethod: Method? by lazy {
        peripheralClass?.runCatching {
            getMethod("invoke", String::class.java, Array<Any>::class.java)
        }?.getOrNull()
    }

    /**
     * `ComputerPeripheral.getMethodsNames()` — protected. We force-access for
     * introspection. One-time setAccessible cost per JVM.
     */
    private val getMethodsNamesMethod: Method? by lazy {
        peripheralClass?.runCatching {
            val m = getDeclaredMethod("getMethodsNames")
            m.isAccessible = true
            m
        }?.getOrNull()
    }

    override fun canHandle(be: BlockEntity, face: Direction): Boolean {
        val cls = ifaceClass ?: return false
        return cls.isInstance(be)
    }

    override fun wrap(be: BlockEntity, face: Direction, name: String, location: com.brewingcoder.oc2.platform.Position): BridgePeripheral? {
        val cls = ifaceClass ?: return null
        if (!cls.isInstance(be)) return null
        val getConnector = getConnectorMethod ?: return null
        val connector = getConnector.invoke(be, face) ?: return null
        val getPeripheral = connectorGetPeripheralMethod ?: return null
        val peripheral = getPeripheral.invoke(connector) ?: return null
        return ZeroCorePeripheral(name, peripheral, location)
    }

    private fun tryLoad(fqn: String): Class<*>? = try {
        Class.forName(fqn)
    } catch (t: Throwable) {
        OpenComputers2.LOGGER.warn("ZeroCoreAdapter: cannot resolve $fqn", t)
        null
    }

    private class ZeroCorePeripheral(
        override val name: String,
        private val backing: Any,
        override val location: com.brewingcoder.oc2.platform.Position,
    ) : BridgePeripheral {
        override val protocol: String = "zerocore"
        override val target: String = backing.javaClass.name

        override fun methods(): List<String> {
            val m = getMethodsNamesMethod ?: return emptyList()
            return try {
                @Suppress("UNCHECKED_CAST")
                val arr = m.invoke(backing) as? Array<String> ?: return emptyList()
                arr.toList()
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.warn("ZeroCoreAdapter.methods() failed", t)
                emptyList()
            }
        }

        override fun call(method: String, args: List<Any?>): Any? {
            val invoke = invokeMethod ?: return null
            return try {
                val raw = invoke.invoke(backing, method, args.toTypedArray()) as? Array<*>
                when {
                    raw == null || raw.isEmpty() -> null
                    raw.size == 1 -> raw[0]
                    else -> raw.toList()
                }
            } catch (t: Throwable) {
                // Quiet the world-unload race: ZeroCore's `CodeHelper.enqueueTask`
                // dereferences `LogicalSidedProvider` which goes null while the
                // server is shutting down. There's no API to query "are we mid
                // shutdown", so we pattern-match the NPE chain instead and downgrade
                // to debug. Real failures still log at warn.
                if (isShutdownNpe(t)) {
                    OpenComputers2.LOGGER.debug("ZeroCoreAdapter.call({}) skipped during shutdown race", method)
                } else {
                    OpenComputers2.LOGGER.warn("ZeroCoreAdapter.call($method) failed", t)
                }
                null
            }
        }

        private fun isShutdownNpe(t: Throwable): Boolean {
            var c: Throwable? = t
            while (c != null) {
                if (c is NullPointerException) {
                    val trace = c.stackTrace
                    if (trace.any { it.className.endsWith("LogicalSidedProvider") }) return true
                }
                c = c.cause
            }
            return false
        }
    }
}
