package com.brewingcoder.oc2.platform.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Reusable contract tests for [WritableMount]. Concrete suites supply a fresh
 * mount via [newMount] (with the requested capacity) and the contract exercises
 * the operations that the VM's filesystem host will hit.
 *
 * Used by [InMemoryMountTest] and (via subclassing) the disk-backed
 * `WorldFileMountTest`. Keeping the contract here ensures both impls behave
 * identically — that's the whole point of the [WritableMount] seam.
 */
abstract class WritableMountContract {

    protected abstract fun newMount(capacity: Long = Long.MAX_VALUE): WritableMount

    @Test
    fun `fresh mount has empty root`() {
        val m = newMount()
        m.exists("") shouldBe true
        m.isDirectory("") shouldBe true
        m.list("") shouldBe emptyList()
    }

    @Test
    fun `write then read round-trips bytes`() {
        val m = newMount()
        m.openForWrite("greeting.txt").use { it.write(buf("hello, world")) }
        m.exists("greeting.txt") shouldBe true
        m.size("greeting.txt") shouldBe 12L
        readAll(m, "greeting.txt") shouldBe "hello, world"
    }

    @Test
    fun `nested directory is created on demand`() {
        val m = newMount()
        m.openForWrite("a/b/c.txt").use { it.write(buf("nested")) }
        m.isDirectory("a") shouldBe true
        m.isDirectory("a/b") shouldBe true
        m.exists("a/b/c.txt") shouldBe true
        readAll(m, "a/b/c.txt") shouldBe "nested"
    }

    @Test
    fun `list returns only direct children`() {
        val m = newMount()
        m.openForWrite("top.txt").use { it.write(buf("a")) }
        m.makeDirectory("dir")
        m.openForWrite("dir/inner.txt").use { it.write(buf("b")) }
        m.list("") shouldContainExactlyInAnyOrder listOf("top.txt", "dir")
        m.list("dir") shouldContainExactlyInAnyOrder listOf("inner.txt")
    }

    @Test
    fun `openForWrite truncates existing file`() {
        val m = newMount()
        m.openForWrite("f.txt").use { it.write(buf("longer original")) }
        m.openForWrite("f.txt").use { it.write(buf("short")) }
        readAll(m, "f.txt") shouldBe "short"
    }

    @Test
    fun `openForAppend extends existing file`() {
        val m = newMount()
        m.openForWrite("log.txt").use { it.write(buf("line 1\n")) }
        m.openForAppend("log.txt").use { it.write(buf("line 2\n")) }
        readAll(m, "log.txt") shouldBe "line 1\nline 2\n"
    }

    @Test
    fun `delete removes a file and frees space`() {
        val m = newMount(capacity = 1024)
        m.openForWrite("f.txt").use { it.write(buf("XXXXXXXX")) }  // 8 bytes
        val afterWrite = m.remainingSpace()
        m.delete("f.txt")
        m.exists("f.txt") shouldBe false
        m.remainingSpace() shouldBe (afterWrite + 8)
    }

    @Test
    fun `delete recursively removes a directory and its files`() {
        val m = newMount()
        m.openForWrite("dir/a.txt").use { it.write(buf("a")) }
        m.openForWrite("dir/sub/b.txt").use { it.write(buf("b")) }
        m.delete("dir")
        m.exists("dir") shouldBe false
        m.exists("dir/a.txt") shouldBe false
        m.exists("dir/sub/b.txt") shouldBe false
    }

    @Test
    fun `rename moves a file`() {
        val m = newMount()
        m.openForWrite("old.txt").use { it.write(buf("payload")) }
        m.rename("old.txt", "new.txt")
        m.exists("old.txt") shouldBe false
        readAll(m, "new.txt") shouldBe "payload"
    }

    @Test
    fun `capacity is respected — write past cap returns short count`() {
        val m = newMount(capacity = 16)
        val ch = m.openForWrite("big.bin")
        ch.use {
            val written = it.write(buf("x".repeat(64)))
            // Should have written at most 16 bytes and refused the rest.
            written shouldBe 16
        }
        m.size("big.bin") shouldBe 16L
        m.remainingSpace() shouldBe 0L
    }

    @Test
    fun `path traversal is rejected`() {
        val m = newMount()
        shouldThrow<StorageException> { m.exists("../escape") }
        shouldThrow<StorageException> { m.openForWrite("a/../../escape") }
    }

    @Test
    fun `listing a missing path throws`() {
        val m = newMount()
        shouldThrow<StorageException> { m.list("does-not-exist") }
    }

    @Test
    fun `listing a file throws`() {
        val m = newMount()
        m.openForWrite("f.txt").use { it.write(buf("a")) }
        shouldThrow<StorageException> { m.list("f.txt") }
    }

    @Test
    fun `cannot delete the root`() {
        val m = newMount()
        shouldThrow<StorageException> { m.delete("") }
    }

    protected fun buf(s: String): ByteBuffer = ByteBuffer.wrap(s.toByteArray(StandardCharsets.UTF_8))

    protected fun readAll(m: Mount, path: String): String {
        m.openForRead(path).use { ch ->
            val size = ch.size().toInt()
            val out = ByteBuffer.allocate(size)
            while (out.hasRemaining()) {
                val n = ch.read(out)
                if (n < 0) break
            }
            return String(out.array(), 0, out.position(), StandardCharsets.UTF_8)
        }
    }
}
