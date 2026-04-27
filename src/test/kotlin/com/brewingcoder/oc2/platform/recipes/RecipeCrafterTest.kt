package com.brewingcoder.oc2.platform.recipes

import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.CrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral
import com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot
import com.brewingcoder.oc2.platform.peripheral.MachineCrafterPeripheral
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.peripheral.RecipeIngredient
import com.brewingcoder.oc2.platform.script.ScriptEnv
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for [RecipeCrafter] — the auto-discovery `recipes.craft(itemId, count)`
 * orchestrator. Uses fake peripherals (no MC) so we exercise resolution + sourcing
 * logic without booting a real Mek factory.
 */
class RecipeCrafterTest {

    private class FakeInv(
        override val name: String,
        private val items: Map<String, Int>,
    ) : InventoryPeripheral {
        override val location: Position = Position.ORIGIN
        private val snapshots = items.entries.map { (id, n) -> ItemSnapshot(id, n) }
        override fun size(): Int = snapshots.size
        override fun getItem(slot: Int): ItemSnapshot? = snapshots.getOrNull(slot - 1)
        override fun list(): List<ItemSnapshot?> = snapshots
        override fun find(itemId: String): Int =
            snapshots.indexOfFirst { it?.id == itemId }.let { if (it < 0) -1 else it + 1 }
        override fun push(slot: Int, target: InventoryPeripheral, count: Int, targetSlot: Int?): Int = 0
        override fun pull(source: InventoryPeripheral, slot: Int, count: Int, targetSlot: Int?): Int = 0
        override fun destroy(slot: Int, count: Int): Int = 0
    }

    private class FakeMachineCrafter(
        override val name: String,
        private val cards: List<MachineCrafterPeripheral.CardSnapshot?>,
        private val cyclesPerCall: Int = Int.MAX_VALUE,
    ) : MachineCrafterPeripheral {
        override val location: Position = Position.ORIGIN
        val calls = mutableListOf<Triple<Int, Int, String>>()

        override fun size(): Int = cards.size
        override fun list(): List<MachineCrafterPeripheral.CardSnapshot?> = cards
        override fun craft(slot: Int, count: Int, source: InventoryPeripheral, fluidSource: FluidPeripheral?): Int {
            calls += Triple(slot, count, source.name)
            return count.coerceAtMost(cyclesPerCall)
        }
        override fun adjacentBlock(): String? = "mod:fake"
    }

    private class FakeEnv(
        private val crafters: List<MachineCrafterPeripheral> = emptyList(),
        private val tableCrafters: List<CrafterPeripheral> = emptyList(),
        private val invs: List<InventoryPeripheral> = emptyList(),
    ) : ScriptEnv {
        override val mount: WritableMount = InMemoryMount(1024)
        override val cwd: String = ""
        override val out: ShellOutput = object : ShellOutput {
            override fun println(line: String) {}
            override fun clear() {}
        }
        override val network: NetworkAccess = NetworkAccess.NOOP
        override fun findPeripheral(kind: String): Peripheral? = listPeripherals(kind).firstOrNull()
        override fun listPeripherals(kind: String?): List<Peripheral> = when (kind) {
            "machine_crafter" -> crafters
            "crafter"         -> tableCrafters
            "inventory"       -> invs
            else              -> crafters + tableCrafters + invs
        }
    }

    private fun machineCard(
        slot: Int,
        output: String,
        outputCount: Int,
        inputs: List<Pair<String, Int>>,
    ) = MachineCrafterPeripheral.CardSnapshot(
        slot = slot,
        output = output,
        outputCount = outputCount,
        fluidIn = null,
        fluidInMb = 0,
        blocking = false,
        inputs = inputs.map { (id, n) -> RecipeIngredient(id, n) },
    )

    @Test
    fun `craft rounds cycles up so produced is at least requested`() {
        // 1 coal -> 8 mini_coal. Asking for 9 should run 2 cycles → 16 produced.
        val mc = FakeMachineCrafter(
            "mc",
            listOf(machineCard(1, "mod:mini_coal", 8, listOf("minecraft:coal" to 1))),
        )
        val inv = FakeInv("chest", mapOf("minecraft:coal" to 64))
        val env = FakeEnv(crafters = listOf(mc), invs = listOf(inv))

        val produced = RecipeCrafter(env).craft("mod:mini_coal", 9)

        produced shouldBe 16
        mc.calls shouldBe listOf(Triple(1, 2, "chest"))
    }

    @Test
    fun `craft accepts unique tail-match for namespace-less ids`() {
        val mc = FakeMachineCrafter(
            "mc",
            listOf(machineCard(1, "bigreactors:graphite_ingot", 1, listOf("minecraft:coal" to 1))),
        )
        val inv = FakeInv("chest", mapOf("minecraft:coal" to 4))
        val env = FakeEnv(crafters = listOf(mc), invs = listOf(inv))

        RecipeCrafter(env).craft("graphite_ingot", 3) shouldBe 3
        mc.calls shouldBe listOf(Triple(1, 3, "chest"))
    }

    @Test
    fun `craft errors on ambiguous tail-match`() {
        val mc1 = FakeMachineCrafter("mc1", listOf(machineCard(1, "modA:graphite_ingot", 1, listOf("minecraft:coal" to 1))))
        val mc2 = FakeMachineCrafter("mc2", listOf(machineCard(1, "modB:graphite_ingot", 1, listOf("minecraft:coal" to 1))))
        val inv = FakeInv("chest", mapOf("minecraft:coal" to 64))
        val env = FakeEnv(crafters = listOf(mc1, mc2), invs = listOf(inv))

        val ex = shouldThrow<IllegalStateException> { RecipeCrafter(env).craft("graphite_ingot", 1) }
        ex.message!! shouldContain "ambiguous"
        ex.message!! shouldContain "modA:graphite_ingot"
        ex.message!! shouldContain "modB:graphite_ingot"
    }

    @Test
    fun `craft errors when no card stamps the requested output`() {
        val mc = FakeMachineCrafter("mc", listOf(machineCard(1, "mod:foo", 1, listOf("minecraft:coal" to 1))))
        val env = FakeEnv(crafters = listOf(mc), invs = listOf(FakeInv("chest", mapOf("minecraft:coal" to 4))))

        val ex = shouldThrow<IllegalStateException> { RecipeCrafter(env).craft("mod:bar", 1) }
        ex.message!! shouldContain "no recipe known"
        ex.message!! shouldContain "mod:foo"
    }

    @Test
    fun `craft errors when no inventory holds enough ingredients for the cycle count`() {
        val mc = FakeMachineCrafter(
            "mc",
            listOf(machineCard(1, "mod:bar", 1, listOf("minecraft:coal" to 4))),
        )
        // Need 4 × 3 = 12 coal; only 8 available.
        val inv = FakeInv("chest", mapOf("minecraft:coal" to 8))
        val env = FakeEnv(crafters = listOf(mc), invs = listOf(inv))

        val ex = shouldThrow<IllegalStateException> { RecipeCrafter(env).craft("mod:bar", 3) }
        ex.message!! shouldContain "no inventory"
        ex.message!! shouldContain "mod:bar"
        mc.calls.isEmpty() shouldBe true
    }

    @Test
    fun `exact-id match wins over tail match when both candidates exist`() {
        val mc = FakeMachineCrafter(
            "mc",
            listOf(
                machineCard(1, "modA:ingot", 1, listOf("minecraft:coal" to 1)),
                machineCard(2, "modB:ingot", 2, listOf("minecraft:coal" to 1)),
            ),
        )
        val inv = FakeInv("chest", mapOf("minecraft:coal" to 64))
        val env = FakeEnv(crafters = listOf(mc), invs = listOf(inv))

        // Exact id "modA:ingot" matches slot 1, not slot 2.
        RecipeCrafter(env).craft("modA:ingot", 1) shouldBe 1
        mc.calls.first() shouldBe Triple(1, 1, "chest")
    }

    @Test
    fun `craft chooses the inventory that holds all ingredients`() {
        val mc = FakeMachineCrafter(
            "mc",
            listOf(
                machineCard(
                    1, "mod:alloy", 1,
                    listOf("minecraft:iron_ingot" to 1, "minecraft:coal" to 1),
                ),
            ),
        )
        // First inventory only has coal — should be skipped. Second has both — chosen.
        val coalOnly = FakeInv("coal_chest", mapOf("minecraft:coal" to 64))
        val both = FakeInv("alloy_chest", mapOf("minecraft:iron_ingot" to 32, "minecraft:coal" to 32))
        val env = FakeEnv(crafters = listOf(mc), invs = listOf(coalOnly, both))

        RecipeCrafter(env).craft("mod:alloy", 1) shouldBe 1
        mc.calls.first().third shouldBe "alloy_chest"
    }
}
