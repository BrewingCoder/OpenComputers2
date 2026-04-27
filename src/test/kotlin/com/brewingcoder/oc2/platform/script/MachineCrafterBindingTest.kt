package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.MachineCrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Lua + JS parity tests for the `machine_crafter` peripheral binding. Uses a
 * fake in-memory machine crafter — no MC, no IItemHandler, no FluidHandler.
 */
class MachineCrafterBindingTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

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

    private class FakeFluid(val tag: String) : FluidPeripheral {
        override val name: String = tag
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        override fun tanks(): Int = 1
        override fun getFluid(tank: Int): FluidPeripheral.FluidSnapshot? = null
        override fun list(): List<FluidPeripheral.FluidSnapshot?> = listOf(null)
        override fun push(target: FluidPeripheral, amount: Int): Int = 0
        override fun pull(source: FluidPeripheral, amount: Int): Int = 0
        override fun destroy(amount: Int): Int = 0
    }

    private class FakeMachineCrafter(
        override val name: String,
        private val cards: List<MachineCrafterPeripheral.CardSnapshot?>,
        private val makesPerCall: Int = 1,
    ) : MachineCrafterPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        val crafts: MutableList<Crafting> = mutableListOf()

        override fun size(): Int = cards.size
        override fun list(): List<MachineCrafterPeripheral.CardSnapshot?> = cards
        override fun craft(slot: Int, count: Int, source: InventoryPeripheral, fluidSource: FluidPeripheral?): Int {
            crafts.add(Crafting(slot, count, source.name, (fluidSource as? FakeFluid)?.tag))
            return makesPerCall.coerceAtMost(count)
        }
        override fun adjacentBlock(): String? = "mekanism:enrichment_chamber"

        data class Crafting(val slot: Int, val count: Int, val sourceName: String, val fluidSourceTag: String?)
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        private val crafter: MachineCrafterPeripheral,
        private val inv: InventoryPeripheral,
        private val fluid: FluidPeripheral? = null,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? = when (kind) {
            "machine_crafter" -> crafter
            "inventory"       -> inv
            "fluid"           -> fluid
            else              -> null
        }
        override fun listPeripherals(kind: String?): List<Peripheral> {
            val all = listOfNotNull<Peripheral>(crafter, inv, fluid)
            return when (kind) {
                null              -> all
                "machine_crafter" -> listOf(crafter)
                "inventory"       -> listOf(inv)
                "fluid"           -> listOfNotNull(fluid)
                else              -> emptyList()
            }
        }
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun mount(): WritableMount = InMemoryMount(4096)

    private fun cards(): List<MachineCrafterPeripheral.CardSnapshot?> = listOf(
        MachineCrafterPeripheral.CardSnapshot(
            slot = 1,
            output = "mekanism:enriched_iron",
            outputCount = 1,
            fluidIn = null,
            fluidInMb = 0,
            blocking = false,
        ),
        null,
        MachineCrafterPeripheral.CardSnapshot(
            slot = 3,
            output = "create:polished_rose_quartz",
            outputCount = 1,
            fluidIn = "minecraft:water",
            fluidInMb = 250,
            blocking = true,
        ),
    )

    // ---------- Lua ----------

    @Test
    fun `lua reads kind name and size`() {
        val c = FakeMachineCrafter("mc1", cards())
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local c = peripheral.find("machine_crafter")
            print(c.kind)
            print(c.name)
            print(c.size())
            print(c.adjacentBlock())
            """.trimIndent(),
            "machine_crafter.lua", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("machine_crafter", "mc1", "3", "mekanism:enrichment_chamber")
    }

    @Test
    fun `lua list yields card snapshots with fluid and blocking flags`() {
        val c = FakeMachineCrafter("mc1", cards())
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local list = peripheral.find("machine_crafter"):list()
            for i = 1, 3 do
              local s = list[i]
              if s then
                local fluid = s.fluidIn or "none"
                print(s.slot .. " " .. s.output .. " x" .. s.outputCount .. " fluid=" .. fluid .. "@" .. s.fluidInMb .. " block=" .. tostring(s.blocking))
              else
                print("nil")
              end
            end
            """.trimIndent(),
            "machine_crafter.lua", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf(
            "1 mekanism:enriched_iron x1 fluid=none@0 block=false",
            "nil",
            "3 create:polished_rose_quartz x1 fluid=minecraft:water@250 block=true",
        )
    }

    @Test
    fun `lua craft passes slot count and source through`() {
        val c = FakeMachineCrafter("mc1", cards(), makesPerCall = 3)
        val inv = FakeInv("source")
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local c = peripheral.find("machine_crafter")
            local s = peripheral.find("inventory")
            print(c:craft(1, 5, s))
            """.trimIndent(),
            "machine_crafter.lua", FakeEnv(mount(), "", out, c, inv),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("3")
        c.crafts shouldBe listOf(FakeMachineCrafter.Crafting(1, 5, "source", null))
    }

    @Test
    fun `lua craft passes optional fluid source`() {
        val c = FakeMachineCrafter("mc1", cards(), makesPerCall = 2)
        val inv = FakeInv("src")
        val fluid = FakeFluid("tank-water")
        val out = CapturingOut()
        val r = CobaltLuaHost().eval(
            """
            local c = peripheral.find("machine_crafter")
            local s = peripheral.find("inventory")
            local f = peripheral.find("fluid")
            print(c:craft(3, 4, s, f))
            """.trimIndent(),
            "machine_crafter.lua", FakeEnv(mount(), "", out, c, inv, fluid),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("2")
        c.crafts shouldBe listOf(FakeMachineCrafter.Crafting(3, 4, "src", "tank-water"))
    }

    // ---------- JS ----------

    @Test
    fun `js reads kind name and size`() {
        val c = FakeMachineCrafter("mc1", cards())
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var c = peripheral.find('machine_crafter');
            print(c.kind);
            print(c.name);
            print(c.size());
            print(c.adjacentBlock());
            """.trimIndent(),
            "machine_crafter.js", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("machine_crafter", "mc1", "3", "mekanism:enrichment_chamber")
    }

    @Test
    fun `js list yields card snapshots with fluid and blocking flags`() {
        val c = FakeMachineCrafter("mc1", cards())
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var list = peripheral.find('machine_crafter').list();
            for (var i = 0; i < 3; i++) {
              var s = list[i];
              if (s) {
                var fluid = s.fluidIn || 'none';
                print(s.slot + ' ' + s.output + ' x' + s.outputCount + ' fluid=' + fluid + '@' + s.fluidInMb + ' block=' + s.blocking);
              } else {
                print('nil');
              }
            }
            """.trimIndent(),
            "machine_crafter.js", FakeEnv(mount(), "", out, c, FakeInv("inv")),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf(
            "1 mekanism:enriched_iron x1 fluid=none@0 block=false",
            "nil",
            "3 create:polished_rose_quartz x1 fluid=minecraft:water@250 block=true",
        )
    }

    @Test
    fun `js craft passes slot count and source through`() {
        val c = FakeMachineCrafter("mc1", cards(), makesPerCall = 2)
        val inv = FakeInv("source")
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var c = peripheral.find('machine_crafter');
            var s = peripheral.find('inventory');
            print(c.craft(2, 4, s));
            """.trimIndent(),
            "machine_crafter.js", FakeEnv(mount(), "", out, c, inv),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("2")
        c.crafts shouldBe listOf(FakeMachineCrafter.Crafting(2, 4, "source", null))
    }

    @Test
    fun `js craft passes optional fluid source`() {
        val c = FakeMachineCrafter("mc1", cards(), makesPerCall = 1)
        val inv = FakeInv("src")
        val fluid = FakeFluid("steam-tank")
        val out = CapturingOut()
        val r = RhinoJSHost().eval(
            """
            var c = peripheral.find('machine_crafter');
            var s = peripheral.find('inventory');
            var f = peripheral.find('fluid');
            print(c.craft(1, 1, s, f));
            """.trimIndent(),
            "machine_crafter.js", FakeEnv(mount(), "", out, c, inv, fluid),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("1")
        c.crafts shouldBe listOf(FakeMachineCrafter.Crafting(1, 1, "src", "steam-tank"))
    }
}
