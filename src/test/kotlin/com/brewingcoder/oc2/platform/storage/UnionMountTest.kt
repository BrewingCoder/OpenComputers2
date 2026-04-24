package com.brewingcoder.oc2.platform.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class UnionMountTest {

    private fun primary() = InMemoryMount()
    private fun rom() = InMemoryMount().apply {
        openForWrite("lib/ui_v1.lua").use { it.write(buf("return { kind='ui_v1' }")) }
        openForWrite("lib/greet.lua").use { it.write(buf("return 42")) }
        openForWrite("boot.lua").use { it.write(buf("-- rom boot")) }
    }

    @Test
    fun `rom path is visible at rom prefix`() {
        val u = UnionMount(primary(), rom())
        u.exists("rom/lib/ui_v1.lua") shouldBe true
        u.isDirectory("rom/lib") shouldBe true
        u.size("rom/boot.lua") shouldBe 11L
    }

    @Test
    fun `root listing includes rom alongside primary entries`() {
        val p = primary().apply { openForWrite("my.txt").use { it.write(buf("x")) } }
        val u = UnionMount(p, rom())
        u.list("") shouldContainExactlyInAnyOrder listOf("my.txt", "rom")
    }

    @Test
    fun `list under rom delegates to fallback`() {
        val u = UnionMount(primary(), rom())
        u.list("rom/lib") shouldContainExactlyInAnyOrder listOf("ui_v1.lua", "greet.lua")
    }

    @Test
    fun `write to primary leaves rom untouched`() {
        val u = UnionMount(primary(), rom())
        u.openForWrite("hello.txt").use { it.write(buf("hi")) }
        u.exists("hello.txt") shouldBe true
        u.exists("rom/hello.txt") shouldBe false
    }

    @Test
    fun `write to rom path throws`() {
        val u = UnionMount(primary(), rom())
        shouldThrow<StorageException> { u.openForWrite("rom/attempt.txt") }
        shouldThrow<StorageException> { u.openForAppend("rom/lib/ui_v1.lua") }
        shouldThrow<StorageException> { u.makeDirectory("rom/new") }
        shouldThrow<StorageException> { u.delete("rom/boot.lua") }
    }

    @Test
    fun `rename into or out of rom throws`() {
        val p = primary().apply { openForWrite("x.txt").use { it.write(buf("hi")) } }
        val u = UnionMount(p, rom())
        shouldThrow<StorageException> { u.rename("x.txt", "rom/moved.txt") }
        shouldThrow<StorageException> { u.rename("rom/boot.lua", "boot.lua") }
    }

    @Test
    fun `read of rom file returns rom bytes`() {
        val u = UnionMount(primary(), rom())
        val ch = u.openForRead("rom/lib/ui_v1.lua")
        val buf = ByteBuffer.allocate(128)
        while (ch.read(buf) > 0) { /* drain */ }
        ch.close()
        String(buf.array(), 0, buf.position(), Charsets.UTF_8) shouldBe "return { kind='ui_v1' }"
    }

    @Test
    fun `capacity and remaining flow through primary`() {
        val p = InMemoryMount(capacityBytes = 1024L)
        val u = UnionMount(p, rom())
        u.capacity() shouldBe 1024L
        u.remainingSpace() shouldBe 1024L
        u.openForWrite("a.txt").use { it.write(buf("x".repeat(10))) }
        u.remainingSpace() shouldBe 1014L
    }

    @Test
    fun `primary content does not leak through at rom path`() {
        // If the user somehow wrote /rom/foo to primary, it stays invisible —
        // the prefix is reserved for the fallback ROM.
        val p = primary()
        // Cannot write to /rom on a UnionMount (blocked). Simulate by writing directly to primary
        // (as if it pre-existed) and then wrap.
        p.makeDirectory("rom")
        p.openForWrite("rom/secret.txt").use { it.write(buf("leaked?")) }
        val u = UnionMount(p, rom())
        u.exists("rom/secret.txt") shouldBe false  // routed to rom, which doesn't have it
        u.list("rom") shouldNotContain "secret.txt"
    }

    @Test
    fun `root listing deduplicates when user already has rom dir in primary`() {
        val p = primary().apply { makeDirectory("rom") }
        val u = UnionMount(p, rom())
        // Still only one "rom" entry, not two.
        u.list("") shouldContain "rom"
        u.list("").count { it == "rom" } shouldBe 1
    }

    @Test
    fun `construction rejects invalid prefix`() {
        shouldThrow<IllegalArgumentException> { UnionMount(primary(), rom(), romPrefix = "") }
        shouldThrow<IllegalArgumentException> { UnionMount(primary(), rom(), romPrefix = "a/b") }
    }

    private fun buf(s: String): ByteBuffer = ByteBuffer.wrap(s.toByteArray(Charsets.UTF_8))
}
