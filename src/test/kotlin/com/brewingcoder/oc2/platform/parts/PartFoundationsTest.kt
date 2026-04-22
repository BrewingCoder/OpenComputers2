package com.brewingcoder.oc2.platform.parts

import com.brewingcoder.oc2.platform.peripheral.Peripheral
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit-level coverage of the platform-pure part foundations: registry, capability
 * lookup contract, and the [CapabilityBackedPart] lifecycle. No MC types here.
 */
class PartFoundationsTest {

    @BeforeEach fun reset() = PartRegistry.clearForTest()
    @AfterEach fun cleanup() = PartRegistry.clearForTest()

    /** Trivial capability + part to exercise the base. */
    private class FakeCap(val name: String)
    private class FakePeripheral(val backing: FakeCap) : Peripheral {
        override val kind: String = "fake"
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
    }
    private class FakePart(key: CapabilityKey<FakeCap>) : CapabilityBackedPart<FakeCap>("fake", key) {
        var attaches: Int = 0
        var neighborChanges: Int = 0
        var detaches: Int = 0
        override fun onAttach(host: PartHost) { attaches++; super.onAttach(host) }
        override fun onNeighborChanged(host: PartHost) { neighborChanges++; super.onNeighborChanged(host) }
        override fun onDetach() { detaches++; super.onDetach() }
        override fun wrapAsPeripheral(cap: FakeCap, location: com.brewingcoder.oc2.platform.Position): Peripheral = FakePeripheral(cap)
    }

    private class FakeHost(
        override val faceId: String,
        private val capLookup: (CapabilityKey<*>) -> Any?,
    ) : PartHost {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        override fun defaultLabel(typeId: String): String = "${typeId}_${faceId}"
        @Suppress("UNCHECKED_CAST")
        override fun <C : Any> lookupCapability(key: CapabilityKey<C>, sideOverride: String?): C? = capLookup(key) as? C
        override fun readRedstoneSignal(): Int = 0
        override fun writeRedstoneSignal(level: Int) {}
        override fun readAdjacentBlock(): com.brewingcoder.oc2.platform.peripheral.BlockPeripheral.BlockReadout? = null
        override fun harvestAdjacentBlock(target: com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral?): List<com.brewingcoder.oc2.platform.peripheral.InventoryPeripheral.ItemSnapshot> = emptyList()
        override fun adjacentBlockEntity(): Any? = null
    }

    @Test
    fun `registry rejects duplicates and resolves by id`() {
        val type = object : PartType {
            override val id = "x"
            override fun create() = throw NotImplementedError()
        }
        PartRegistry.register(type)
        PartRegistry.get("x") shouldBe type
        PartRegistry.get("missing") shouldBe null
        runCatching { PartRegistry.register(type) }.isFailure shouldBe true
    }

    @Test
    fun `CapabilityBackedPart returns null peripheral when capability absent`() {
        val key = CapabilityKey<FakeCap>("fake")
        val part = FakePart(key)
        part.onAttach(FakeHost("north") { null })
        part.asPeripheral() shouldBe null
    }

    @Test
    fun `CapabilityBackedPart wraps capability as peripheral when present`() {
        val key = CapabilityKey<FakeCap>("fake")
        val cap = FakeCap("hello")
        val part = FakePart(key)
        part.onAttach(FakeHost("north") { cap })
        val p = part.asPeripheral()
        (p as? FakePeripheral)?.backing?.name shouldBe "hello"
    }

    @Test
    fun `defaultLabel auto-generated when label empty on attach`() {
        val key = CapabilityKey<FakeCap>("fake")
        val part = FakePart(key)
        part.label shouldBe ""
        part.onAttach(FakeHost("east") { FakeCap("c") })
        part.label shouldBe "fake_east"
    }

    @Test
    fun `neighborChanged refreshes the cached capability`() {
        val key = CapabilityKey<FakeCap>("fake")
        var current: FakeCap? = FakeCap("v1")
        val host = FakeHost("up") { current }
        val part = FakePart(key)
        part.onAttach(host)
        (part.asPeripheral() as FakePeripheral).backing.name shouldBe "v1"
        current = FakeCap("v2")
        part.onNeighborChanged(host)
        (part.asPeripheral() as FakePeripheral).backing.name shouldBe "v2"
        current = null
        part.onNeighborChanged(host)
        part.asPeripheral() shouldBe null
    }

    @Test
    fun `onDetach drops the cached capability`() {
        val key = CapabilityKey<FakeCap>("fake")
        val part = FakePart(key)
        part.onAttach(FakeHost("down") { FakeCap("alive") })
        part.onDetach()
        part.asPeripheral() shouldBe null
        part.detaches shouldBe 1
    }
}
