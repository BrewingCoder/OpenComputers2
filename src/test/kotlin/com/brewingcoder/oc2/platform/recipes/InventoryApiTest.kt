package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.ScriptEnv
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InventoryApi]. All-fake fixtures — no Cobalt, no MC.
 */
class InventoryApiTest {

    private object SilentOut : ShellOutput {
        override fun println(line: String) {}
        override fun clear() {}
    }

    /** Tiny in-memory inventory: list of (id, count) backed by mutable list of stacks. */
    private class FakeInv(override val name: String, slots: Int) : InventoryPeripheral {
        override val location: Position = Position.ORIGIN
        private val stacks: MutableList<ItemSnapshot?> = MutableList(slots) { null }

        fun setSlot(slot: Int, snap: ItemSnapshot?) { stacks[slot - 1] = snap }
        fun fill(id: String, count: Int) {
            // Pack `count` items into one stack at slot 1.
            stacks[0] = ItemSnapshot(id, count)
        }

        override fun size(): Int = stacks.size
        override fun getItem(slot: Int): ItemSnapshot? = stacks.getOrNull(slot - 1)
        override fun list(): List<ItemSnapshot?> = stacks.toList()
        override fun find(itemId: String): Int =
            stacks.indexOfFirst { it?.id == itemId }.let { if (it < 0) -1 else it + 1 }

        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int {
            val src = stacks.getOrNull(slot - 1) ?: return 0
            val tgt = target as FakeInv
            val moveCount = count.coerceAtMost(src.count)
            // Try to merge into an existing stack of the same id, then fall back to first empty slot.
            val tIdx = if (targetSlot != null) {
                targetSlot
            } else {
                val mergeIdx = tgt.stacks.indexOfFirst { it?.id == src.id }
                if (mergeIdx >= 0) mergeIdx + 1
                else tgt.stacks.indexOfFirst { it == null }.let { if (it < 0) return 0 else it + 1 }
            }
            val existing = tgt.stacks[tIdx - 1]
            tgt.stacks[tIdx - 1] = if (existing == null)
                ItemSnapshot(src.id, moveCount)
            else
                ItemSnapshot(existing.id, existing.count + moveCount)
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

    private class FakeEnv(private val invs: List<InventoryPeripheral>) : ScriptEnv {
        override val mount: WritableMount = InMemoryMount(4096)
        override val cwd: String = ""
        override val out: ShellOutput = SilentOut
        override fun findPeripheral(kind: String): Peripheral? =
            if (kind == "inventory") invs.firstOrNull() else null
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null || kind == "inventory") invs else emptyList()
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    // ---------- list / find ----------

    @Test
    fun `list returns every InventoryPeripheral on the channel`() {
        val a = FakeInv("a", 3)
        val b = FakeInv("b", 3)
        val api = InventoryApi(FakeEnv(listOf(a, b)))
        api.list() shouldBe listOf(a, b)
    }

    @Test
    fun `find with no name returns first inventory`() {
        val a = FakeInv("a", 3)
        val b = FakeInv("b", 3)
        val api = InventoryApi(FakeEnv(listOf(a, b)))
        api.find() shouldBe a
    }

    @Test
    fun `find by name returns the matching inventory`() {
        val a = FakeInv("a", 3)
        val b = FakeInv("b", 3)
        val api = InventoryApi(FakeEnv(listOf(a, b)))
        api.find("b") shouldBe b
    }

    @Test
    fun `find by missing name returns null`() {
        val a = FakeInv("a", 3)
        val api = InventoryApi(FakeEnv(listOf(a)))
        api.find("nope") shouldBe null
    }

    // ---------- get ----------

    @Test
    fun `get drains the smallest pile first`() {
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val medium = FakeInv("medium", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 20)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val target = FakeInv("target", 3)
        val api = InventoryApi(FakeEnv(listOf(small, medium, large, target)))

        val moved = api.get("minecraft:iron_ingot", 10, target)

        moved shouldBe 10
        // small fully drained, medium contributed 5, large untouched.
        small.list()[0] shouldBe null
        medium.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 15)
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 64)
        target.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 10)
    }

    @Test
    fun `get respects explicit from list order`() {
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val target = FakeInv("target", 3)
        val api = InventoryApi(FakeEnv(listOf(small, large, target)))

        // Override default ranking: pull from `large` first, then `small`.
        val moved = api.get("minecraft:iron_ingot", 10, target, from = listOf(large, small))

        moved shouldBe 10
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 54)
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 5)
    }

    @Test
    fun `get excludes the target from auto-discovered sources`() {
        val target = FakeInv("target", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 99)) }
        val api = InventoryApi(FakeEnv(listOf(target)))

        // No external sources — target should not pull from itself.
        api.get("minecraft:iron_ingot", 10, target) shouldBe 0
        target.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 99)
    }

    @Test
    fun `get returns 0 when nothing has the item`() {
        val a = FakeInv("a", 3).apply { setSlot(1, ItemSnapshot("minecraft:dirt", 5)) }
        val target = FakeInv("target", 3)
        val api = InventoryApi(FakeEnv(listOf(a, target)))
        api.get("minecraft:iron_ingot", 10, target) shouldBe 0
    }

    @Test
    fun `get caps at requested count`() {
        val a = FakeInv("a", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 100)) }
        val target = FakeInv("target", 3)
        val api = InventoryApi(FakeEnv(listOf(a, target)))
        api.get("minecraft:iron_ingot", 7, target) shouldBe 7
        a.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 93)
    }

    // ---------- drain ----------

    @Test
    fun `drain pushes to the largest sink first`() {
        val machine = FakeInv("machine", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 8)) }
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val api = InventoryApi(FakeEnv(listOf(machine, small, large)))

        val moved = api.drain(machine)

        moved shouldBe 8
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 72)
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 5)
        machine.list()[0] shouldBe null
    }

    @Test
    fun `drain filters by itemId when provided`() {
        val machine = FakeInv("machine", 3).apply {
            setSlot(1, ItemSnapshot("minecraft:iron_ingot", 8))
            setSlot(2, ItemSnapshot("minecraft:dirt", 3))
        }
        val sink = FakeInv("sink", 3)
        val api = InventoryApi(FakeEnv(listOf(machine, sink)))

        api.drain(machine, "minecraft:iron_ingot") shouldBe 8

        machine.list()[0] shouldBe null
        machine.list()[1] shouldBe ItemSnapshot("minecraft:dirt", 3)
        sink.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 8)
    }

    @Test
    fun `drain respects explicit to list order`() {
        val machine = FakeInv("machine", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 8)) }
        val small = FakeInv("small", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 5)) }
        val large = FakeInv("large", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 64)) }
        val api = InventoryApi(FakeEnv(listOf(machine, small, large)))

        // Override default: dump into `small` first.
        api.drain(machine, to = listOf(small, large)) shouldBe 8
        small.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 13)
        large.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 64)
    }

    @Test
    fun `drain returns 0 when machine is empty`() {
        val machine = FakeInv("machine", 3)
        val sink = FakeInv("sink", 3)
        val api = InventoryApi(FakeEnv(listOf(machine, sink)))
        api.drain(machine) shouldBe 0
    }

    // ---------- put ----------

    @Test
    fun `put moves explicit count between explicit endpoints`() {
        val source = FakeInv("source", 3).apply { setSlot(1, ItemSnapshot("minecraft:iron_ingot", 30)) }
        val target = FakeInv("target", 3)
        val api = InventoryApi(FakeEnv(listOf(source, target)))

        api.put("minecraft:iron_ingot", 12, source, target) shouldBe 12

        source.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 18)
        target.list()[0] shouldBe ItemSnapshot("minecraft:iron_ingot", 12)
    }

    @Test
    fun `put returns 0 when source has no matching item`() {
        val source = FakeInv("source", 3).apply { setSlot(1, ItemSnapshot("minecraft:dirt", 30)) }
        val target = FakeInv("target", 3)
        val api = InventoryApi(FakeEnv(listOf(source, target)))
        api.put("minecraft:iron_ingot", 12, source, target) shouldBe 0
    }
}
