package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Bridge-call argument marshalling: Lua → Java for mod peripheral dispatch.
 *
 * Regression guard against the rod-setter silence bug: ZeroCore's
 * `ComputerPeripheral.invoke(method, Object[])` dispatches reflectively and
 * casts each arg to its declared type. CC:T's convention — numbers are
 * `Double`, tables are `Map<Double, Object>` — is what the mod peripherals
 * expect. If we narrow whole Lua numbers to `Integer`, the cast fails
 * silently and the call no-ops.
 */
class BridgeCallArgsTest {

    private class CapturingBridge(override val name: String) : BridgePeripheral {
        override val location: Position = Position.ORIGIN
        override val protocol: String = "test"
        override val target: String = "test"
        val callLog = mutableListOf<Pair<String, List<Any?>>>()
        override fun methods(): List<String> = listOf("setLevel", "setAll", "setMany")
        override fun call(method: String, args: List<Any?>): Any? {
            callLog.add(method to args)
            return null
        }
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        private val bridge: BridgePeripheral,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? =
            if (kind == "bridge") bridge else null
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null || kind == "bridge") listOf(bridge) else emptyList()
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private class NullOut : ShellOutput {
        override fun println(line: String) {}
        override fun clear() {}
    }

    private fun env(bridge: BridgePeripheral) =
        FakeEnv(InMemoryMount(1024), "/", NullOut(), bridge)

    @Test
    fun `lua integer args arrive as Double not Integer`() {
        // ZeroCore/CC:T peripherals expect Double for numeric args; narrowing
        // to Integer triggers silent ClassCastException → null. This test
        // proves we send Double.
        val bridge = CapturingBridge("r")
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("bridge")
            r:call("setLevel", 0, 50)
        """.trimIndent(), "t.lua", env(bridge))
        r.ok shouldBe true

        bridge.callLog.size shouldBe 1
        val (name, args) = bridge.callLog[0]
        name shouldBe "setLevel"
        args.size shouldBe 2
        args[0].shouldBeInstanceOf<Double>()
        args[1].shouldBeInstanceOf<Double>()
        (args[0] as Double) shouldBe 0.0
        (args[1] as Double) shouldBe 50.0
    }

    @Test
    fun `lua float args arrive as Double`() {
        val bridge = CapturingBridge("r")
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("bridge")
            r:call("setAll", 15.5)
        """.trimIndent(), "t.lua", env(bridge))
        r.ok shouldBe true

        val (_, args) = bridge.callLog[0]
        args[0].shouldBeInstanceOf<Double>()
        (args[0] as Double) shouldBe 15.5
    }

    @Test
    fun `lua table arg arrives as Map with Double keys not stringified`() {
        // Regression guard: tables used to hit the `else -> v.toString()`
        // branch and arrive as "table: 0x1234".
        val bridge = CapturingBridge("r")
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("bridge")
            local arr = {}
            for i = 1, 3 do arr[i] = i * 10 end
            r:call("setMany", arr)
        """.trimIndent(), "t.lua", env(bridge))
        r.ok shouldBe true

        val (_, args) = bridge.callLog[0]
        args.size shouldBe 1
        val m = args[0]
        m.shouldBeInstanceOf<Map<*, *>>()
        @Suppress("UNCHECKED_CAST")
        val map = m as Map<Any, Any?>
        map.size shouldBe 3
        map.containsKey(1.0) shouldBe true
        map.containsKey(2.0) shouldBe true
        map.containsKey(3.0) shouldBe true
        (map[1.0] as Double) shouldBe 10.0
        (map[2.0] as Double) shouldBe 20.0
        (map[3.0] as Double) shouldBe 30.0
    }

    @Test
    fun `lua boolean args remain Boolean`() {
        // Regression guard: setActive(true) worked before the fix — don't
        // break it now.
        val bridge = CapturingBridge("r")
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("bridge")
            r:call("setLevel", true, false)
        """.trimIndent(), "t.lua", env(bridge))
        r.ok shouldBe true

        val (_, args) = bridge.callLog[0]
        args.shouldContainExactly(listOf(true, false))
    }

    @Test
    fun `lua string args remain String`() {
        val bridge = CapturingBridge("r")
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("bridge")
            r:call("setLevel", "probe")
        """.trimIndent(), "t.lua", env(bridge))
        r.ok shouldBe true

        val (_, args) = bridge.callLog[0]
        args[0] shouldBe "probe"
    }

    @Test
    fun `lua nested table arrives as nested Map`() {
        val bridge = CapturingBridge("r")
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("bridge")
            r:call("setMany", { outer = { inner = 42 } })
        """.trimIndent(), "t.lua", env(bridge))
        r.ok shouldBe true

        val (_, args) = bridge.callLog[0]
        @Suppress("UNCHECKED_CAST")
        val map = args[0] as Map<Any, Any?>
        val outer = map["outer"] as Map<*, *>
        outer["inner"] shouldBe 42.0
    }
}
