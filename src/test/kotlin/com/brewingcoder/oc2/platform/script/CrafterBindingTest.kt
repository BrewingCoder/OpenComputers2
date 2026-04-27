package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.CrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Lua + JS parity tests for the `crafter` peripheral binding. Uses a fake
 * in-memory crafter — no MC, no recipe manager.
 */
class CrafterBindingTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

    /** Minimal inventory stub — never touched by these tests except as the source argument. */
    private class FakeInv(override val name: String) : InventoryPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        override fun size(): Int = 9
        override fun getItem(slot: Int): ItemSnapshot? = null
        override fun list(): List<ItemSnapshot?> = List(9) { null }
        override fun find(itemId: String): Int = -1
        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int = 0
        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int = 0
        override fun destroy(slot: Int, count: Int): Int = 0
    }

    /** Fake crafter: programmable card slots + records every craft() invocation. */
    private class FakeCrafter(
        override val name: String,
        private val cards: List<CrafterPeripheral.CardSnapshot?>,
        private val makesPerCall: Int = 1,
    ) : CrafterPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        val crafts: MutableList<Triple<Int, Int, String>> = mutableListOf()

        override fun size(): Int = cards.size
        override fun list(): List<CrafterPeripheral.CardSnapshot?> = cards
        override fun craft(slot: Int, count: Int, source: InventoryPeripheral): Int {
            crafts.add(Triple(slot, count, source.name))
            return makesPerCall.coerceAtMost(count)
        }
        override fun adjacentBlock(): String? = "minecraft:crafting_table"
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        private val crafter: CrafterPeripheral,
        private val inv: InventoryPeripheral,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? = when (kind) {
            "crafter"   -> crafter
            "inventory" -> inv
            else        -> null
        }
        override fun listPeripherals(kind: String?): List<Peripheral> = when (kind) {
            null        -> listOf(crafter, inv)
            "crafter"   -> listOf(crafter)
            "inventory" -> listOf(inv)
            else        -> emptyList()
        }
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun mount(): WritableMount = InMemoryMount(4096)

    private fun cards(): List<CrafterPeripheral.CardSnapshot?> = listOf(
        CrafterPeripheral.CardSnapshot(slot = 1, output = "minecraft:torch", outputCount = 4),
        null,
        CrafterPeripheral.CardSnapshot(slot = 3, output = "oc2:unmatched", outputCount = 0),
    )

    // ---------- Lua ----------

    @Test
    fun `lua reads size and name`() {
        val c = FakeCrafter("c1", cards())
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local c = peripheral.find("crafter")
            print(c.kind)
            print(c.name)
            print(c.size())
            """.trimIndent(),
            "crafter.lua", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("crafter", "c1", "3")
    }

    @Test
    fun `lua list yields card snapshots with nils for empty slots`() {
        val c = FakeCrafter("c1", cards())
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local list = peripheral.find("crafter"):list()
            for i = 1, 3 do
              local s = list[i]
              if s then
                print(s.slot .. " " .. s.output .. " x" .. s.outputCount)
              else
                print("nil")
              end
            end
            """.trimIndent(),
            "crafter.lua", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("1 minecraft:torch x4", "nil", "3 oc2:unmatched x0")
    }

    @Test
    fun `lua craft passes slot count and source through`() {
        val c = FakeCrafter("c1", cards(), makesPerCall = 3)
        val inv = FakeInv("source")
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local c = peripheral.find("crafter")
            local s = peripheral.find("inventory")
            print(c:craft(1, 5, s))
            """.trimIndent(),
            "crafter.lua", FakeEnv(mount(), "", out, c, inv),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("3")
        c.crafts shouldBe listOf(Triple(1, 5, "source"))
    }

    // ---------- JS ----------

    @Test
    fun `js reads size and name`() {
        val c = FakeCrafter("c1", cards())
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var c = peripheral.find('crafter');
            print(c.kind);
            print(c.name);
            print(c.size());
            """.trimIndent(),
            "crafter.js", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("crafter", "c1", "3")
    }

    @Test
    fun `js list yields card snapshots`() {
        val c = FakeCrafter("c1", cards())
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var list = peripheral.find('crafter').list();
            for (var i = 0; i < 3; i++) {
              var s = list[i];
              if (s) print(s.slot + ' ' + s.output + ' x' + s.outputCount);
              else   print('nil');
            }
            """.trimIndent(),
            "crafter.js", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("1 minecraft:torch x4", "nil", "3 oc2:unmatched x0")
    }

    @Test
    fun `js craft passes slot count and source through`() {
        val c = FakeCrafter("c1", cards(), makesPerCall = 2)
        val inv = FakeInv("source")
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var c = peripheral.find('crafter');
            var s = peripheral.find('inventory');
            print(c.craft(2, 4, s));
            """.trimIndent(),
            "crafter.js", FakeEnv(mount(), "", out, c, inv),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("2")
        c.crafts shouldBe listOf(Triple(2, 4, "source"))
    }
}
