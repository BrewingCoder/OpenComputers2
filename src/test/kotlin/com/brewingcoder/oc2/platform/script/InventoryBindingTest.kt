package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Lua + JS parity tests for the `inventory` peripheral binding. Uses a fake
 * in-memory inventory — no NeoForge `IItemHandler`, no MC at all.
 */
class InventoryBindingTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

    /** Tiny in-memory inventory: list of (id, count) backed by mutable list of stacks. */
    private class FakeInv(override val name: String, slots: Int) : InventoryPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
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
            // simple: stuff into first empty slot if no targetSlot
            val tIdx = (targetSlot ?: tgt.stacks.indexOfFirst { it == null }.let { if (it < 0) return 0 else it + 1 })
            tgt.stacks[tIdx - 1] = ItemSnapshot(src.id, moveCount)
            stacks[slot - 1] = if (src.count - moveCount <= 0) null else ItemSnapshot(src.id, src.count - moveCount)
            return moveCount
        }
        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int {
            return (source as FakeInv).push(slot, this, count, targetSlot)
        }
        override fun destroy(slot: Int, count: Int): Int {
            val src = stacks.getOrNull(slot - 1) ?: return 0
            val killed = count.coerceAtMost(src.count)
            stacks[slot - 1] = if (src.count - killed <= 0) null else ItemSnapshot(src.id, src.count - killed)
            return killed
        }
    }

    /** Env that returns predefined inventory peripherals on `peripheral.list("inventory")`. */
    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        private val invs: List<InventoryPeripheral>,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? =
            if (kind == "inventory") invs.firstOrNull() else null
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null || kind == "inventory") invs else emptyList()
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun mount(): WritableMount = InMemoryMount(4096)

    // ---------- Lua ----------

    @Test
    fun `lua reads slot count and item id`() {
        val a = FakeInv("a", 9).apply { setSlot(1, ItemSnapshot("minecraft:diamond", 5)) }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local inv = peripheral.find("inventory")
            print(inv.size())
            print(inv.name)
            local s = inv.getItem(1)
            print(s.id, s.count)
            print(inv.getItem(2) == nil)
        """.trimIndent(), "inv.lua", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf("9", "a", "minecraft:diamond\t5", "true")
    }

    @Test
    fun `lua list returns whole snapshot with nils for empty slots`() {
        val a = FakeInv("a", 3).apply {
            setSlot(1, ItemSnapshot("minecraft:dirt", 64))
            setSlot(3, ItemSnapshot("minecraft:stone", 17))
        }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local list = peripheral.find("inventory").list()
            for i = 1, 3 do
              local s = list[i]
              if s == nil then print(i, "empty") else print(i, s.id, s.count) end
            end
        """.trimIndent(), "list.lua", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf("1\tminecraft:dirt\t64", "2\tempty", "3\tminecraft:stone\t17")
    }

    @Test
    fun `lua push moves items between two inventories`() {
        val src = FakeInv("src", 3).apply { setSlot(1, ItemSnapshot("minecraft:diamond", 10)) }
        val dst = FakeInv("dst", 3)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local invs = peripheral.list("inventory")
            local src, dst
            for _, p in ipairs(invs) do
              if p.name == "src" then src = p else dst = p end
            end
            local moved = src.push(1, dst, 4)
            print(moved)
            print(src.getItem(1).count)
            print(dst.getItem(1).id, dst.getItem(1).count)
        """.trimIndent(), "push.lua", FakeEnv(mount(), "", out, listOf(src, dst)))
        r.ok shouldBe true
        out.lines shouldBe listOf("4", "6", "minecraft:diamond\t4")
    }

    @Test
    fun `lua destroy voids items without sending them anywhere`() {
        val a = FakeInv("a", 3).apply { setSlot(1, ItemSnapshot("minecraft:diamond", 10)) }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local inv = peripheral.find("inventory")
            print(inv.destroy(1, 4))     -- killed
            print(inv.getItem(1).count)   -- 6 left
            print(inv.destroy(1, 99))    -- only 6 left, kills them all
            print(inv.getItem(1) == nil) -- empty now
        """.trimIndent(), "destroy.lua", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf("4", "6", "6", "true")
    }

    @Test
    fun `lua find returns 1-indexed slot or -1`() {
        val a = FakeInv("a", 5).apply { setSlot(3, ItemSnapshot("minecraft:gold_ingot", 1)) }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local inv = peripheral.find("inventory")
            print(inv.find("minecraft:gold_ingot"))
            print(inv.find("minecraft:diamond"))
        """.trimIndent(), "find.lua", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf("3", "-1")
    }

    @Test
    fun `lua peripheral methods accept both colon-call and dot-call syntax`() {
        // Regression: prior to the `method(self) {...}` helper, `inv:foo(x)` desugared
        // to `inv.foo(inv, x)` and the wrapper read `inv` as the first user arg —
        // resulting in `tostring(table)` ("table: HASH") landing where `x` should be.
        val a = FakeInv("a", 5).apply { setSlot(2, ItemSnapshot("minecraft:emerald", 7)) }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local inv = peripheral.find("inventory")
            -- dot syntax (no implicit self)
            print(inv.getItem(2).id, inv.getItem(2).count)
            -- colon syntax (implicit self prepended) MUST give the same result
            print(inv:getItem(2).id, inv:getItem(2).count)
            -- size() with no args via both
            print(inv.size(), inv:size())
        """.trimIndent(), "colon.lua", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf(
            "minecraft:emerald\t7",
            "minecraft:emerald\t7",
            "5\t5",
        )
    }

    // ---------- JS ----------

    @Test
    fun `js reads slot count and item id`() {
        val a = FakeInv("a", 9).apply { setSlot(1, ItemSnapshot("minecraft:diamond", 5)) }
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var inv = peripheral.find("inventory");
            print(inv.size());
            print(inv.name);
            var s = inv.getItem(1);
            print(s.id + " " + s.count);
            print(inv.getItem(2) == null);
        """.trimIndent(), "inv.js", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf("9", "a", "minecraft:diamond 5", "true")
    }

    @Test
    fun `js push moves items between two inventories`() {
        val src = FakeInv("src", 3).apply { setSlot(1, ItemSnapshot("minecraft:diamond", 10)) }
        val dst = FakeInv("dst", 3)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var invs = peripheral.list("inventory");
            var src, dst;
            for (var i = 0; i < invs.length; i++) {
              if (invs[i].name === "src") src = invs[i]; else dst = invs[i];
            }
            print(src.push(1, dst, 4));
            print(src.getItem(1).count);
            print(dst.getItem(1).id + " " + dst.getItem(1).count);
        """.trimIndent(), "push.js", FakeEnv(mount(), "", out, listOf(src, dst)))
        r.ok shouldBe true
        out.lines shouldBe listOf("4", "6", "minecraft:diamond 4")
    }

    @Test
    fun `js find returns 1-indexed slot or -1`() {
        val a = FakeInv("a", 5).apply { setSlot(3, ItemSnapshot("minecraft:gold_ingot", 1)) }
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var inv = peripheral.find("inventory");
            print(inv.find("minecraft:gold_ingot"));
            print(inv.find("minecraft:diamond"));
        """.trimIndent(), "find.js", FakeEnv(mount(), "", out, listOf(a)))
        r.ok shouldBe true
        out.lines shouldBe listOf("3", "-1")
    }
}
