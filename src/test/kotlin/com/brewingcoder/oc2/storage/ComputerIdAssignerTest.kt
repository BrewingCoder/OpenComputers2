package com.brewingcoder.oc2.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ComputerIdAssignerTest {

    @Test
    fun `first assignment per kind starts at 0 and increments`() {
        val path = Files.createTempFile("ids", ".json")
        Files.delete(path)  // assigner starts from "no file"
        val a = ComputerIdAssigner(path)
        a.assign("computer") shouldBe 0
        a.assign("computer") shouldBe 1
        a.assign("computer") shouldBe 2
    }

    @Test
    fun `independent kinds increment independently`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        val a = ComputerIdAssigner(path)
        a.assign("computer") shouldBe 0
        a.assign("disk") shouldBe 0
        a.assign("computer") shouldBe 1
        a.snapshot() shouldBe mapOf("computer" to 2, "disk" to 1)
    }

    @Test
    fun `state survives close-and-reopen`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        ComputerIdAssigner(path).apply {
            assign("computer"); assign("computer"); assign("computer")  // -> next=3
        }
        val reopened = ComputerIdAssigner(path)
        reopened.assign("computer") shouldBe 3
    }

    @Test
    fun `tolerates blank lines and comments`() {
        val path = Files.createTempFile("ids", ".json")
        // Legacy bare-key format (pre-2026-04-18) should still parse
        Files.writeString(path, "# this is a comment\n\ncomputer=42\n  \n")
        val a = ComputerIdAssigner(path)
        a.assign("computer") shouldBe 42
        a.assign("computer") shouldBe 43
    }

    // ---------- assignFor (idempotent, location-keyed) ----------

    @Test
    fun `assignFor returns the same id for the same locationKey`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        val a = ComputerIdAssigner(path)
        a.assignFor("computer", "ow.1_2_3") shouldBe 0
        a.assignFor("computer", "ow.1_2_3") shouldBe 0
        a.assignFor("computer", "ow.1_2_3") shouldBe 0
    }

    @Test
    fun `assignFor allocates fresh ids for new locations`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        val a = ComputerIdAssigner(path)
        a.assignFor("computer", "ow.1_2_3") shouldBe 0
        a.assignFor("computer", "ow.4_5_6") shouldBe 1
        a.assignFor("computer", "ow.7_8_9") shouldBe 2
        a.assignFor("computer", "ow.1_2_3") shouldBe 0  // first one is sticky
    }

    @Test
    fun `assignFor and assign share the same kind counter`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        val a = ComputerIdAssigner(path)
        a.assign("computer") shouldBe 0
        a.assignFor("computer", "ow.1_2_3") shouldBe 1
        a.assign("computer") shouldBe 2
    }

    @Test
    fun `assignFor survives close-and-reopen — the crash-recovery test`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        ComputerIdAssigner(path).assignFor("computer", "ow.91_80_16") shouldBe 0
        // Simulate the crash scenario: BE NBT was never saved, but ids.json was.
        // Next launch — same block, same location — should recover id 0.
        val reopened = ComputerIdAssigner(path)
        reopened.assignFor("computer", "ow.91_80_16") shouldBe 0
    }

    @Test
    fun `assignmentsSnapshot exposes all sticky bindings`() {
        val path = Files.createTempFile("ids", ".json").also { Files.delete(it) }
        val a = ComputerIdAssigner(path)
        a.assignFor("computer", "ow.1_2_3")
        a.assignFor("computer", "ow.4_5_6")
        a.assignmentsSnapshot() shouldBe mapOf(
            "computer.ow.1_2_3" to 0,
            "computer.ow.4_5_6" to 1,
        )
    }
}
