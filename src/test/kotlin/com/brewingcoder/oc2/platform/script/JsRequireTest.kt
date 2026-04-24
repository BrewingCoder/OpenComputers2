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

class JsRequireTest {

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

    private fun romMount(): Mount = InMemoryMount().apply {
        openForWrite("lib/ui_v1.js").use {
            it.write(buf("""
                module.exports = {
                  version: 'v1',
                  hello: function() { return 'rom-hello'; }
                };
            """.trimIndent()))
        }
        openForWrite("lib/broken.js").use {
            it.write(buf("this is not valid javascript }"))
        }
    }

    private fun compose(primary: WritableMount, rom: Mount): WritableMount =
        UnionMount(primary, rom)

    @Test
    fun `require loads a js module from rom_lib`() {
        val primary = InMemoryMount()
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = RhinoJSHost().eval(
            """
            var m = require("ui_v1");
            print(m.version);
            print(m.hello());
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("v1", "rom-hello")
    }

    @Test
    fun `require caches module across multiple calls`() {
        val primary = InMemoryMount()
        val rom = InMemoryMount().apply {
            openForWrite("lib/counter.js").use {
                it.write(buf("""
                    if (typeof globalThis.LOAD_COUNT === 'undefined') globalThis.LOAD_COUNT = 0;
                    globalThis.LOAD_COUNT = globalThis.LOAD_COUNT + 1;
                    module.exports = globalThis.LOAD_COUNT;
                """.trimIndent()))
            }
        }
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, rom), out = out)
        val r = RhinoJSHost().eval(
            """
            var a = require("counter");
            var b = require("counter");
            print(a + "," + b);
            print(globalThis.LOAD_COUNT);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("1,1", "1")
    }

    @Test
    fun `user lib shadows rom lib`() {
        val primary = InMemoryMount().apply {
            openForWrite("lib/ui_v1.js").use {
                it.write(buf("module.exports = { version: 'user-override' };"))
            }
        }
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = RhinoJSHost().eval(
            """
            var m = require("ui_v1");
            print(m.version);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("user-override")
    }

    @Test
    fun `missing module throws and names searched paths`() {
        val primary = InMemoryMount()
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = RhinoJSHost().eval(
            """require("does_not_exist");""",
            "test.js", env,
        )
        r.ok shouldBe false
        r.errorMessage shouldContain "module 'does_not_exist' not found"
        r.errorMessage shouldContain "/lib/does_not_exist.js"
        r.errorMessage shouldContain "/rom/lib/does_not_exist.js"
    }

    @Test
    fun `module with syntax error surfaces a compile error`() {
        val primary = InMemoryMount()
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, romMount()), out = out)
        val r = RhinoJSHost().eval(
            """require("broken");""",
            "test.js", env,
        )
        r.ok shouldBe false
        r.errorMessage shouldContain "compile error in module 'broken'"
    }

    @Test
    fun `module can use this to assign exports`() {
        val primary = InMemoryMount()
        val rom = InMemoryMount().apply {
            openForWrite("lib/thisy.js").use {
                // `this` inside the wrapper is module.exports — node compat
                it.write(buf("this.ping = function() { return 'pong'; };"))
            }
        }
        val out = CapturingOut()
        val env = FakeEnv(compose(primary, rom), out = out)
        val r = RhinoJSHost().eval(
            """
            var m = require("thisy");
            print(m.ping());
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("pong")
    }
}
