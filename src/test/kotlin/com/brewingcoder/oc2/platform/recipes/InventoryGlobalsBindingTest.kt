package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.BridgePeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.CobaltLuaHost
import com.brewingcoder.oc2.platform.script.RhinoJSHost
import com.brewingcoder.oc2.platform.script.ScriptEnv
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Parity tests for the `inventory` and `recipes` script-side globals
 * across Lua and JS hosts. Mirrors the patterns in
 * [com.brewingcoder.oc2.platform.script.InventoryBindingTest].
 */
class InventoryGlobalsBindingTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

    /** Tiny in-memory inventory with id-aware merging. */
    private class FakeInv(override val name: String, slots: Int) : InventoryPeripheral {
        override val location: Position = Position.ORIGIN
        private val stacks: MutableList<ItemSnapshot?> = MutableList(slots) { null }
        fun setSlot(slot: Int, snap: ItemSnapshot?) { stacks[slot - 1] = snap }
        override fun size(): Int = stacks.size
        override fun getItem(slot: Int): ItemSnapshot? = stacks.getOrNull(slot - 1)
        override fun list(): List<ItemSnapshot?> = stacks.toList()
        override fun find(itemId: String): Int =
            stacks.indexOfFirst { it?.id == itemId }.let { if (it < 0) -1 else it + 1 }
        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int {
            val src = stacks.getOrNull(slot - 1) ?: return 0
            val moveCount = count.coerceAtMost(src.count)
            val tgt = target as FakeInv
            val tIdx = if (targetSlot != null) targetSlot
            else {
                val mergeIdx = tgt.stacks.indexOfFirst { it?.id == src.id }
                if (mergeIdx >= 0) mergeIdx + 1
                else tgt.stacks.indexOfFirst { it == null }.let { if (it < 0) return 0 else it + 1 }
            }
            val existing = tgt.stacks[tIdx - 1]
            tgt.stacks[tIdx - 1] = if (existing == null) ItemSnapshot(src.id, moveCount)
            else ItemSnapshot(existing.id, existing.count + moveCount)
            stacks[slot - 1] = if (src.count - moveCount <= 0) null
            else ItemSnapshot(src.id, src.count - moveCount)
            return moveCount
        }
        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int =
            (source as FakeInv).push(slot, this, count, targetSlot)
        override fun destroy(slot: Int, count: Int): Int {
            val src = stacks.getOrNull(slot - 1) ?: return 0
            val killed = count.coerceAtMost(src.count)
            stacks[slot - 1] = if (src.count - killed <= 0) null
            else ItemSnapshot(src.id, src.count - killed)
            return killed
        }
    }

    /** Fake bridge with a tiny smelting recipe table. */
    private class FakeBridge(
        override val name: String,
        private val recipes: Map<String, List<List<String>>>,
    ) : BridgePeripheral {
        override val location: Position = Position.ORIGIN
        override val protocol: String = "test"
        override val target: String = "test:fake_bridge"
        override fun methods(): List<String> =
            listOf("canConsume", "canProduce", "getInputsFor", "getOutputFor")
        override fun call(method: String, args: List<Any?>): Any? {
            val id = args.firstOrNull() as? String
            return when (method) {
                "canConsume" -> id != null && recipes.values.any { v -> v.any { it.contains(id) } }
                "canProduce" -> id != null && recipes.containsKey(id)
                "getOutputFor" -> {
                    if (id == null) return null
                    val match = recipes.entries.firstOrNull { (_, v) -> v.any { it.contains(id) } }
                        ?: return null
                    mapOf("id" to match.key, "count" to 1)
                }
                "getInputsFor" -> {
                    if (id == null) return emptyList<Any>()
                    (recipes[id] ?: return emptyList<Any>()).map { mapOf("inputs" to it, "count" to 1) }
                }
                else -> null
            }
        }
    }

    private class FakeEnv(private val all: List<Peripheral>) : ScriptEnv {
        override val mount: WritableMount = InMemoryMount(4096)
        override val cwd: String = ""
        override val out: ShellOutput = CapturingOut()
        override fun findPeripheral(kind: String): Peripheral? =
            all.firstOrNull { it.kind == kind }
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null) all else all.filter { it.kind == kind }
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun envWith(out: CapturingOut, vararg ps: Peripheral): ScriptEnv {
        return object : ScriptEnv {
            override val mount: WritableMount = InMemoryMount(4096)
            override val cwd: String = ""
            override val out: ShellOutput = out
            override fun findPeripheral(kind: String): Peripheral? = ps.firstOrNull { it.kind == kind }
            override fun listPeripherals(kind: String?): List<Peripheral> =
                if (kind == null) ps.toList() else ps.filter { it.kind == kind }
            override val network: NetworkAccess = NetworkAccess.NOOP
        }
    }

    // ---------- Lua: inventory ----------

    @Test
    fun `lua inventory list and find return wrapped handles`() {
        val a = FakeInv("a", 3)
        val b = FakeInv("b", 3)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local list = inventory.list()
            print(#list, list[1].name, list[2].name)
            print(inventory.find().name)
            print(inventory.find("b").name)
            print(inventory.find("nope") == nil)
        """.trimIndent(), "inv.lua", envWith(out, a, b))
        r.ok shouldBe true
        out.lines shouldBe listOf("2\ta\tb", "a", "b", "true")
    }

    @Test
    fun `lua inventory get drains smallest pile first`() {
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val medium = FakeInv("medium", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 20)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val target = FakeInv("target", 3)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local target = inventory.find("target")
            local moved = inventory.get("minecraft:iron_ingot", 10, target)
            print(moved)
            print(target.list()[1].count)
        """.trimIndent(), "get.lua", envWith(out, small, medium, large, target))
        r.ok shouldBe true
        out.lines shouldBe listOf("10", "10")
        small.list()[0] shouldBe null
        medium.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 15)
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 64)
    }

    @Test
    fun `lua inventory drain pushes to largest sink`() {
        val machine = FakeInv("machine", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 8)) }
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local moved = inventory.drain(inventory.find("machine"))
            print(moved)
        """.trimIndent(), "drain.lua", envWith(out, machine, small, large))
        r.ok shouldBe true
        out.lines shouldBe listOf("8")
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 72)
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 5)
        machine.list()[0] shouldBe null
    }

    @Test
    fun `lua inventory put moves explicit count between explicit endpoints`() {
        val source = FakeInv("source", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 30)) }
        val target = FakeInv("target", 3)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local s = inventory.find("source")
            local t = inventory.find("target")
            print(inventory.put("minecraft:iron_ingot", 12, s, t))
        """.trimIndent(), "put.lua", envWith(out, source, target))
        r.ok shouldBe true
        out.lines shouldBe listOf("12")
        source.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 18)
        target.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 12)
    }

    @Test
    fun `lua inventory get respects explicit from order`() {
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val target = FakeInv("target", 3)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local t = inventory.find("target")
            local moved = inventory.get("minecraft:iron_ingot", 10, t,
              { inventory.find("large"), inventory.find("small") })
            print(moved)
        """.trimIndent(), "getfrom.lua", envWith(out, small, large, target))
        r.ok shouldBe true
        out.lines shouldBe listOf("10")
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 54)
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 5)
    }

    // ---------- Lua: recipes ----------

    @Test
    fun `lua recipes returns producers and consumers`() {
        val smelter = FakeBridge(
            "smelter",
            mapOf("minecraft:iron_ingot" to listOf(listOf("minecraft:raw_iron"))),
        )
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local q = recipes("minecraft:iron_ingot")
            print(q.itemId)
            print(#q.producers, q.producers[1].name)
            print(#q.inputs, q.inputs[1])
            local q2 = recipes("minecraft:raw_iron")
            print(#q2.consumers, q2.consumers[1].name)
            print(#q2.outputs, q2.outputs[1])
        """.trimIndent(), "recipes.lua", envWith(out, smelter))
        r.ok shouldBe true
        out.lines shouldBe listOf(
            "minecraft:iron_ingot",
            "1\tsmelter",
            "1\tminecraft:raw_iron",
            "1\tsmelter",
            "1\tminecraft:iron_ingot",
        )
    }

    // ---------- JS: inventory ----------

    @Test
    fun `js inventory list and find return wrapped handles`() {
        val a = FakeInv("a", 3)
        val b = FakeInv("b", 3)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var list = inventory.list();
            print(list.length, list[0].name, list[1].name);
            print(inventory.find().name);
            print(inventory.find("b").name);
            print(inventory.find("nope") === null);
        """.trimIndent(), "inv.js", envWith(out, a, b))
        r.ok shouldBe true
        out.lines shouldBe listOf("2 a b", "a", "b", "true")
    }

    @Test
    fun `js inventory get drains smallest pile first`() {
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val medium = FakeInv("medium", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 20)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val target = FakeInv("target", 3)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var t = inventory.find("target");
            print(inventory.get("minecraft:iron_ingot", 10, t));
        """.trimIndent(), "get.js", envWith(out, small, medium, large, target))
        r.ok shouldBe true
        out.lines shouldBe listOf("10")
        small.list()[0] shouldBe null
        medium.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 15)
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 64)
    }

    @Test
    fun `js inventory drain pushes to largest sink`() {
        val machine = FakeInv("machine", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 8)) }
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            print(inventory.drain(inventory.find("machine")));
        """.trimIndent(), "drain.js", envWith(out, machine, small, large))
        r.ok shouldBe true
        out.lines shouldBe listOf("8")
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 72)
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 5)
        machine.list()[0] shouldBe null
    }

    @Test
    fun `js inventory put moves explicit count between explicit endpoints`() {
        val source = FakeInv("source", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 30)) }
        val target = FakeInv("target", 3)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var s = inventory.find("source");
            var t = inventory.find("target");
            print(inventory.put("minecraft:iron_ingot", 12, s, t));
        """.trimIndent(), "put.js", envWith(out, source, target))
        r.ok shouldBe true
        out.lines shouldBe listOf("12")
        source.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 18)
        target.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 12)
    }

    @Test
    fun `js inventory get respects explicit from order`() {
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val target = FakeInv("target", 3)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var t = inventory.find("target");
            print(inventory.get("minecraft:iron_ingot", 10, t,
              [inventory.find("large"), inventory.find("small")]));
        """.trimIndent(), "getfrom.js", envWith(out, small, large, target))
        r.ok shouldBe true
        out.lines shouldBe listOf("10")
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 54)
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 5)
    }

    // ---------- JS: recipes ----------

    @Test
    fun `js recipes returns producers and consumers`() {
        val smelter = FakeBridge(
            "smelter",
            mapOf("minecraft:iron_ingot" to listOf(listOf("minecraft:raw_iron"))),
        )
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var q = recipes("minecraft:iron_ingot");
            print(q.itemId);
            print(q.producers.length, q.producers[0].name);
            print(q.inputs.length, q.inputs[0]);
            var q2 = recipes("minecraft:raw_iron");
            print(q2.consumers.length, q2.consumers[0].name);
            print(q2.outputs.length, q2.outputs[0]);
        """.trimIndent(), "recipes.js", envWith(out, smelter))
        r.ok shouldBe true
        out.lines shouldBe listOf(
            "minecraft:iron_ingot",
            "1 smelter",
            "1 minecraft:raw_iron",
            "1 smelter",
            "1 minecraft:iron_ingot",
        )
    }
}
