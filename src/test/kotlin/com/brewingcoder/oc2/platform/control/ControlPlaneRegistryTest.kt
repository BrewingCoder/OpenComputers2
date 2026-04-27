package com.brewingcoder.oc2.platform.control

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ControlPlaneRegistryTest {

    private fun freshPath(): Path =
        Files.createTempFile("control-planes", ".txt").also { Files.delete(it) }

    private val owner1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val owner2 = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private fun loc(dim: String = "minecraft:overworld", x: Int = 0, y: Int = 64, z: Int = 0) =
        ControlPlaneRegistry.Location(dim, x, y, z)

    // ---------- assign / lookup ----------

    @Test
    fun `assign stores the location and reports back via lookups`() {
        val r = ControlPlaneRegistry(freshPath())
        val l = loc(x = 1, y = 2, z = 3)

        r.assign(owner1, l) shouldBe true
        r.hasOwner(owner1) shouldBe true
        r.locationFor(owner1) shouldBe l
        r.ownerAt(l) shouldBe owner1
    }

    @Test
    fun `assign rejects when owner already has a different location`() {
        val r = ControlPlaneRegistry(freshPath())
        r.assign(owner1, loc(x = 1)) shouldBe true
        r.assign(owner1, loc(x = 99)) shouldBe false
        r.locationFor(owner1)?.x shouldBe 1
    }

    @Test
    fun `assign is idempotent for the same owner+location`() {
        val r = ControlPlaneRegistry(freshPath())
        val l = loc(x = 5)
        r.assign(owner1, l) shouldBe true
        r.assign(owner1, l) shouldBe true
        r.assign(owner1, l) shouldBe true
        r.snapshot() shouldBe mapOf(owner1 to l)
    }

    @Test
    fun `independent owners can each register one location`() {
        val r = ControlPlaneRegistry(freshPath())
        r.assign(owner1, loc(x = 1)) shouldBe true
        r.assign(owner2, loc(x = 2)) shouldBe true
        r.snapshot() shouldHaveSize 2
        r.locationFor(owner1)?.x shouldBe 1
        r.locationFor(owner2)?.x shouldBe 2
    }

    // ---------- release ----------

    @Test
    fun `release clears both maps and lets the owner re-register elsewhere`() {
        val r = ControlPlaneRegistry(freshPath())
        val original = loc(x = 10)
        r.assign(owner1, original) shouldBe true
        r.release(owner1) shouldBe true

        r.hasOwner(owner1) shouldBe false
        r.ownerAt(original).shouldBeNull()
        // and now they can register somewhere else
        r.assign(owner1, loc(x = 99)) shouldBe true
    }

    @Test
    fun `release on an unknown owner returns false`() {
        val r = ControlPlaneRegistry(freshPath())
        r.release(owner1) shouldBe false
    }

    @Test
    fun `releaseAt clears by location regardless of which side knows the key`() {
        val r = ControlPlaneRegistry(freshPath())
        val l = loc(x = 7)
        r.assign(owner1, l) shouldBe true

        r.releaseAt(l) shouldBe true
        r.hasOwner(owner1) shouldBe false
        r.ownerAt(l).shouldBeNull()
    }

    @Test
    fun `releaseAt on an unknown location returns false`() {
        val r = ControlPlaneRegistry(freshPath())
        r.releaseAt(loc(x = 42)) shouldBe false
    }

    // ---------- persistence ----------

    @Test
    fun `state survives close-and-reopen`() {
        val path = freshPath()
        val l1 = loc(dim = "minecraft:the_nether", x = 1, y = 2, z = 3)
        val l2 = loc(dim = "minecraft:overworld", x = 4, y = 5, z = 6)
        ControlPlaneRegistry(path).apply {
            assign(owner1, l1)
            assign(owner2, l2)
        }

        val reopened = ControlPlaneRegistry(path)
        reopened.locationFor(owner1) shouldBe l1
        reopened.locationFor(owner2) shouldBe l2
        reopened.ownerAt(l1) shouldBe owner1
        reopened.ownerAt(l2) shouldBe owner2
    }

    @Test
    fun `release is persisted across reopen`() {
        val path = freshPath()
        ControlPlaneRegistry(path).apply {
            assign(owner1, loc(x = 1))
            assign(owner2, loc(x = 2))
            release(owner1)
        }

        val reopened = ControlPlaneRegistry(path)
        reopened.hasOwner(owner1) shouldBe false
        reopened.hasOwner(owner2) shouldBe true
    }

    @Test
    fun `tolerates blank lines, comments, and malformed entries`() {
        val path = Files.createTempFile("control-planes", ".txt")
        Files.writeString(
            path,
            buildString {
                append("# header comment\n")
                append("\n")
                append("not-a-uuid=minecraft:overworld:1:2:3\n")          // bad UUID
                append("$owner1=garbage\n")                                // bad location
                append("$owner1=minecraft:overworld:7:8:9\n")              // valid
                append("=missing-key\n")
                append("   \n")
            },
        )

        val r = ControlPlaneRegistry(path)
        r.locationFor(owner1) shouldBe loc(x = 7, y = 8, z = 9)
        r.snapshot() shouldHaveSize 1
    }

    // ---------- Location encoding ----------

    @Test
    fun `Location encode and decode round-trip for plain dimension`() {
        val l = loc(dim = "minecraft:overworld", x = -5, y = 320, z = 999)
        ControlPlaneRegistry.Location.decodeOrNull(l.encode()) shouldBe l
    }

    @Test
    fun `Location decode handles dimensions containing colons`() {
        // E.g. some modded dim ids are namespace:path with extra colons
        val l = ControlPlaneRegistry.Location("modid:dim:nether_realm", 1, 2, 3)
        val decoded = ControlPlaneRegistry.Location.decodeOrNull(l.encode())
        decoded.shouldNotBeNull()
        decoded.dimension shouldBe "modid:dim:nether_realm"
        decoded.x shouldBe 1
        decoded.y shouldBe 2
        decoded.z shouldBe 3
    }

    @Test
    fun `Location decode rejects too-short input`() {
        ControlPlaneRegistry.Location.decodeOrNull("foo:1:2").shouldBeNull()
        ControlPlaneRegistry.Location.decodeOrNull("nope").shouldBeNull()
    }

    @Test
    fun `Location decode rejects non-numeric coords`() {
        ControlPlaneRegistry.Location.decodeOrNull("minecraft:overworld:a:b:c").shouldBeNull()
        ControlPlaneRegistry.Location.decodeOrNull("minecraft:overworld:1:2:zzz").shouldBeNull()
    }

    // ---------- snapshot ----------

    @Test
    fun `snapshot is a defensive copy`() {
        val r = ControlPlaneRegistry(freshPath())
        r.assign(owner1, loc(x = 1))
        val snap = r.snapshot()
        r.assign(owner2, loc(x = 2))
        snap shouldHaveSize 1
        snap.keys shouldContain owner1
    }
}
