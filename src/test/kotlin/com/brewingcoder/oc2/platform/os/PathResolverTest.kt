package com.brewingcoder.oc2.platform.os

import com.brewingcoder.oc2.platform.storage.StorageException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PathResolverTest {

    @Test
    fun `empty path yields cwd`() {
        PathResolver.resolve("foo", "") shouldBe "foo"
        PathResolver.resolve("foo", ".") shouldBe "foo"
    }

    @Test
    fun `relative path appends to cwd`() {
        PathResolver.resolve("a/b", "c") shouldBe "a/b/c"
        PathResolver.resolve("", "x") shouldBe "x"
    }

    @Test
    fun `absolute path ignores cwd`() {
        PathResolver.resolve("a/b", "/x/y") shouldBe "x/y"
        PathResolver.resolve("anything", "/") shouldBe ""
    }

    @Test
    fun `dotdot pops one level`() {
        PathResolver.resolve("a/b/c", "..") shouldBe "a/b"
        PathResolver.resolve("a/b", "../x") shouldBe "a/x"
        PathResolver.resolve("a/b/c", "../../x") shouldBe "a/x"
    }

    @Test
    fun `dot is ignored`() {
        PathResolver.resolve("a", "./b") shouldBe "a/b"
        PathResolver.resolve("a", "./") shouldBe "a"
    }

    @Test
    fun `dotdot at root throws`() {
        shouldThrow<StorageException> { PathResolver.resolve("", "..") }
        shouldThrow<StorageException> { PathResolver.resolve("a", "../../..") }
    }

    @Test
    fun `trailing slash is dropped`() {
        PathResolver.resolve("", "foo/") shouldBe "foo"
        PathResolver.resolve("a", "b/") shouldBe "a/b"
    }

    @Test
    fun `repeated slashes collapse`() {
        PathResolver.resolve("", "a//b///c") shouldBe "a/b/c"
    }
}
