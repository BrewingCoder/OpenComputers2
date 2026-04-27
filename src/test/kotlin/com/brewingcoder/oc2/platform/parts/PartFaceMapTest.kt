package com.brewingcoder.oc2.platform.parts

import com.brewingcoder.oc2.platform.peripheral.Peripheral
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PartFaceMapTest {

    /** Stand-in [Part] for instance-identity checks; no MC types. */
    private class StubPart(override val typeId: String) : Part {
        override var label: String = ""
        override var channelId: String = "default"
        override val options: MutableMap<String, String> = mutableMapOf()
        override var data: String = ""
        override fun onAttach(host: PartHost) {}
        override fun asPeripheral(): Peripheral? = null
    }

    @Test
    fun `move re-keys the same instance`() {
        val a = StubPart("inventory")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a)

        PartFaceMap.move(parts, "north", "south") shouldBe true

        parts shouldContainExactly mapOf("south" to a)
        // Same instance — Part.label/channelId/options/etc. all carried over.
        (parts["south"] === a) shouldBe true
    }

    @Test
    fun `move from equals to is a no-op`() {
        val a = StubPart("inventory")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a)

        PartFaceMap.move(parts, "north", "north") shouldBe false
        parts shouldContainExactly mapOf("north" to a)
    }

    @Test
    fun `move from empty face returns false`() {
        val a = StubPart("inventory")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a)

        PartFaceMap.move(parts, "south", "east") shouldBe false
        parts shouldContainExactly mapOf("north" to a)
    }

    @Test
    fun `move to occupied face returns false and leaves both intact`() {
        val a = StubPart("inventory")
        val b = StubPart("fluid")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a, "south" to b)

        PartFaceMap.move(parts, "north", "south") shouldBe false
        parts shouldContainExactly mapOf("north" to a, "south" to b)
    }

    @Test
    fun `swap exchanges instances and preserves both`() {
        val a = StubPart("inventory")
        val b = StubPart("fluid")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a, "south" to b)

        PartFaceMap.swap(parts, "north", "south") shouldBe true

        parts shouldContainExactly mapOf("north" to b, "south" to a)
        (parts["north"] === b) shouldBe true
        (parts["south"] === a) shouldBe true
    }

    @Test
    fun `swap of equal keys is a no-op`() {
        val a = StubPart("inventory")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a)

        PartFaceMap.swap(parts, "north", "north") shouldBe false
        parts shouldContainExactly mapOf("north" to a)
    }

    @Test
    fun `swap with one empty face returns false`() {
        val a = StubPart("inventory")
        val parts: MutableMap<String, Part> = mutableMapOf("north" to a)

        PartFaceMap.swap(parts, "north", "south") shouldBe false
        PartFaceMap.swap(parts, "east", "north") shouldBe false
        parts shouldContainExactly mapOf("north" to a)
    }

    @Test
    fun `swap with both empty faces returns false`() {
        val parts: MutableMap<String, Part> = mutableMapOf()
        PartFaceMap.swap(parts, "north", "south") shouldBe false
        parts.isEmpty() shouldBe true
    }
}
