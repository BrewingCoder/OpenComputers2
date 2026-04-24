package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.Mount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class LuaRequireTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() = Unit
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String = "",
        override val out: ShellOutput,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String) = null
    }

    private fun buf(s: String): ByteBuffer = ByteBuffer.wrap(s.toByteArray(Charsets.UTF_8))

    /** ROM side of the union. Seeded with a `lib/ui_v1.lua` module. */
    private fun romMount(): Mount = InMemoryMount().apply {
        openForWrite("lib/ui_v1.lua").use {
            it.write(buf("return { version = 'v1', hello = function() return 'rom-hello' end }"))
        }
        openForWrite("lib/broken.lua").use {
            it.write(buf("return end"))  // intentional syntax error
        }
    }

    private fun compose(primary: WritableMount, rom: Mount): WritableMount =
        UnionMount(primary, rom)

    @Test
    fun `require loads a lua module from rom_lib`() {
        val primary = InMemoryMount()
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = CobaltLuaHost().eval(
            """
            local m = require("ui_v1")
            print(m.version)
            print(m.hello())
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("v1", "rom-hello")
    }

    @Test
    fun `require caches module across multiple calls`() {
        val primary = InMemoryMount()
        // Module with a side effect: increments and returns a counter. If require
        // cached correctly, the side effect fires exactly once.
        val rom = InMemoryMount().apply {
            openForWrite("lib/counter.lua").use {
                it.write(buf("""
                    _G.LOAD_COUNT = (_G.LOAD_COUNT or 0) + 1
                    return _G.LOAD_COUNT
                """.trimIndent()))
            }
        }
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, rom), out = out)
        val r = CobaltLuaHost().eval(
            """
            local a = require("counter")
            local b = require("counter")
            print(a, b)
            print(_G.LOAD_COUNT)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Both requires return the same cached value; side-effect runs once.
        out.lines shouldBe listOf("1\t1", "1")
    }

    @Test
    fun `user lib shadows rom lib`() {
        val primary = InMemoryMount().apply {
            openForWrite("lib/ui_v1.lua").use {
                it.write(buf("return { version = 'user-override' }"))
            }
        }
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = CobaltLuaHost().eval(
            """
            local m = require("ui_v1")
            print(m.version)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("user-override")
    }

    @Test
    fun `missing module raises a lua error naming the searched paths`() {
        val primary = InMemoryMount()
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = CobaltLuaHost().eval(
            """require("does_not_exist")""",
            "test.lua", env,
        )
        r.ok shouldBe false
        r.errorMessage shouldContain "module 'does_not_exist' not found"
        r.errorMessage shouldContain "/lib/does_not_exist.lua"
        r.errorMessage shouldContain "/rom/lib/does_not_exist.lua"
    }

    @Test
    fun `module with compile error surfaces a compile error`() {
        val primary = InMemoryMount()
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = CobaltLuaHost().eval(
            """require("broken")""",
            "test.lua", env,
        )
        r.ok shouldBe false
        r.errorMessage shouldContain "compile error in module 'broken'"
    }

    @Test
    fun `module returning nothing is cached as true`() {
        val primary = InMemoryMount()
        val rom = InMemoryMount().apply {
            openForWrite("lib/noop.lua").use { it.write(buf("-- no return")) }
        }
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, rom), out = out)
        val r = CobaltLuaHost().eval(
            """
            local a = require("noop")
            print(a)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("true")
    }
}
