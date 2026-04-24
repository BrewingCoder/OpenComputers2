package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.StorageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ResourceMountTest {

    private fun mount() = ResourceMount("test-rom")

    @Test
    fun `root is a directory that exists`() {
        val m = mount()
        m.exists("") shouldBe true
        m.isDirectory("") shouldBe true
    }

    @Test
    fun `list root yields top-level entries only`() {
        val m = mount()
        // `hello.txt` (file) + `lib` (dir)
        m.list("") shouldContainExactlyInAnyOrder listOf("hello.txt", "lib")
    }

    @Test
    fun `nested listing shows lib contents`() {
        val m = mount()
        m.list("lib") shouldContainExactlyInAnyOrder listOf("greet.lua", "greet.js")
        m.isDirectory("lib") shouldBe true
    }

    @Test
    fun `size returns file byte length`() {
        val m = mount()
        // "hello from rom\n" = 15 bytes (UTF-8)
        m.size("hello.txt") shouldBe 15L
    }

    @Test
    fun `openForRead streams file bytes`() {
        val m = mount()
        val ch = m.openForRead("hello.txt")
        val buf = ByteBuffer.allocate(64)
        while (ch.read(buf) > 0) { /* drain */ }
        ch.close()
        String(buf.array(), 0, buf.position(), Charsets.UTF_8) shouldBe "hello from rom\n"
    }

    @Test
    fun `exists returns false for missing path`() {
        val m = mount()
        m.exists("does/not/exist") shouldBe false
    }

    @Test
    fun `size on missing path throws`() {
        val m = mount()
        shouldThrow<StorageException> { m.size("missing.txt") }
    }

    @Test
    fun `openForRead on missing path throws`() {
        val m = mount()
        shouldThrow<StorageException> { m.openForRead("missing.txt") }
    }

    @Test
    fun `list on missing path throws`() {
        val m = mount()
        shouldThrow<StorageException> { m.list("nope") }
    }

    @Test
    fun `list on a file throws`() {
        val m = mount()
        shouldThrow<StorageException> { m.list("hello.txt") }
    }

    @Test
    fun `openForRead on a directory throws`() {
        val m = mount()
        shouldThrow<StorageException> { m.openForRead("lib") }
    }

    @Test
    fun `missing base path yields empty mount, not a failure`() {
        val empty = ResourceMount("no-such-base-path")
        empty.exists("") shouldBe true
        empty.isDirectory("") shouldBe true
        empty.list("") shouldBe emptyList()
    }
}
