package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Mirrors [CobaltLuaHostTest] for the Rhino JS host. Same test cases, different
 * syntax — a behavioral parity check that catches divergence between languages.
 */
class RhinoJSHostTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        var clears = 0
        override fun println(line: String) { lines.add(line) }
        override fun clear() { clears++ }
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        private val finder: (String) -> Peripheral? = { null },
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? = finder(kind)
    }

    private fun host() = RhinoJSHost()
    private fun mount(cap: Long = 4096): WritableMount = InMemoryMount(cap)

    private fun RhinoJSHost.eval(source: String, chunkName: String, out: ShellOutput): ScriptResult =
        eval(source, chunkName, FakeEnv(mount(), "", out))

    private fun RhinoJSHost.eval(source: String, chunkName: String, out: ShellOutput, m: WritableMount, cwd: String): ScriptResult =
        eval(source, chunkName, FakeEnv(m, cwd, out))

    // ---------- core JS ----------

    @Test
    fun `print hello world`() {
        val out = CapturingOut()
        val r = host().eval("""print("hello world");""", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("hello world")
    }

    @Test
    fun `arithmetic and integer formatting`() {
        val out = CapturingOut()
        val r = host().eval("print(1 + 1);", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("2")
    }

    @Test
    fun `print of multiple args is space-separated`() {
        val out = CapturingOut()
        val r = host().eval("""print("a", "b", "c");""", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("a b c")
    }

    @Test
    fun `for-loop emits one line per iteration`() {
        val out = CapturingOut()
        val r = host().eval("for (var i = 1; i <= 10; i++) print(i);", "loop.js", out)
        r.ok shouldBe true
        out.lines shouldBe (1..10).map { it.toString() }
    }

    @Test
    fun `null and booleans render via String conventions`() {
        val out = CapturingOut()
        val r = host().eval("print(null); print(true); print(false);", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("null", "true", "false")
    }

    @Test
    fun `syntax error is captured cleanly`() {
        val out = CapturingOut()
        val r = host().eval("for (var i =", "broken.js", out)
        r.ok shouldBe false
        r.errorMessage!!.shouldContain("js error")
        out.lines shouldBe emptyList()
    }

    @Test
    fun `runtime js error is captured cleanly`() {
        val out = CapturingOut()
        val r = host().eval("""throw new Error("explicit");""", "test.js", out)
        r.ok shouldBe false
        r.errorMessage!!.shouldContain("explicit")
    }

    @Test
    fun `string concatenation works`() {
        val out = CapturingOut()
        val r = host().eval("""print("hello, " + "world");""", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("hello, world")
    }

    @Test
    fun `each eval is isolated — globals do NOT persist across calls`() {
        val h = host()
        val out1 = CapturingOut()
        h.eval("x = 42;", "a.js", out1).ok shouldBe true
        val out2 = CapturingOut()
        h.eval("print(typeof x);", "b.js", out2).ok shouldBe true
        out2.lines shouldBe listOf("undefined")
    }

    @Test
    fun `Math object is available`() {
        val out = CapturingOut()
        val r = host().eval("print(Math.floor(3.7));", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("3")
    }

    @Test
    fun `JSON object is available`() {
        val out = CapturingOut()
        val r = host().eval("""print(JSON.stringify({a: 1, b: "x"}));""", "test.js", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("""{"a":1,"b":"x"}""")
    }

    // ---------- fs API ----------

    @Test
    fun `fs_write then fs_read round-trips`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("hi.txt", "hello");
            print(fs.read("hi.txt"));
        """.trimIndent(), "rw.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("hello")
    }

    @Test
    fun `fs_list returns a JS array`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("a.txt", "1");
            fs.write("b.txt", "2");
            var entries = fs.list("");
            entries.sort();
            for (var i = 0; i < entries.length; i++) print(entries[i]);
        """.trimIndent(), "list.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("a.txt", "b.txt")
    }

    @Test
    fun `fs_exists and fs_isDir behave correctly`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.mkdir("things");
            fs.write("things/file.txt", "x");
            print(fs.exists("things"));
            print(fs.exists("nope"));
            print(fs.isDir("things"));
            print(fs.isDir("things/file.txt"));
        """.trimIndent(), "probe.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("true", "false", "true", "false")
    }

    @Test
    fun `fs_size reports byte length`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("f.txt", "hello!");
            print(fs.size("f.txt"));
        """.trimIndent(), "size.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("6")
    }

    @Test
    fun `fs_append extends existing file`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("log.txt", "line 1");
            fs.append("log.txt", "-line 2");
            print(fs.read("log.txt"));
        """.trimIndent(), "append.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("line 1-line 2")
    }

    @Test
    fun `fs_delete removes a file`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("doomed", "bye");
            print(fs.exists("doomed"));
            fs.delete("doomed");
            print(fs.exists("doomed"));
        """.trimIndent(), "delete.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("true", "false")
    }

    @Test
    fun `fs paths are resolved against the script's cwd`() {
        val out = CapturingOut()
        val m = mount()
        m.makeDirectory("sub")
        m.openForWrite("sub/hi.txt").use {
            val buf = java.nio.ByteBuffer.wrap("from-cwd".toByteArray())
            while (buf.hasRemaining()) it.write(buf)
        }
        val r = host().eval("""print(fs.read("hi.txt"));""", "cwd.js", out, m, "sub")
        r.ok shouldBe true
        out.lines shouldBe listOf("from-cwd")
    }

    @Test
    fun `fs_capacity and fs_free return bytes`() {
        val out = CapturingOut()
        val m = InMemoryMount(capacityBytes = 1024L)
        val r = host().eval("""
            print(fs.capacity());
            fs.write("x.txt", "XXXXXXXX");
            print(fs.free());
        """.trimIndent(), "cap.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("1024", "1016")
    }

    @Test
    fun `fs_read on missing file throws — caught by try-catch`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            try {
                fs.read("nope");
                print("no-throw");
            } catch (e) {
                print("caught: " + e.message);
            }
        """.trimIndent(), "missing.js", out, m, "")
        r.ok shouldBe true
        out.lines.size shouldBe 1
        out.lines[0].shouldContain("caught")
        out.lines[0].shouldContain("no such file")
    }

    @Test
    fun `fs cannot path-traverse out of root`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            try {
                fs.write("../escape", "x");
                print("no-throw");
            } catch (e) {
                print("caught");
            }
        """.trimIndent(), "traverse.js", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("caught")
    }

    // ---------- peripheral API ----------

    private class FakeMonitor(private val cols: Int = 20, private val rows: Int = 10) :
        com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral {
        val log = mutableListOf<String>()
        override fun write(text: String) { log.add("write($text)") }
        override fun setCursorPos(col: Int, row: Int) { log.add("setCursorPos($col,$row)") }
        override fun clear() { log.add("clear()") }
        override fun getSize(): Pair<Int, Int> = cols to rows
        override fun getCursorPos(): Pair<Int, Int> = 0 to 0
        override fun setForegroundColor(color: Int) { log.add("setFg(${"%08X".format(color)})") }
        override fun setBackgroundColor(color: Int) { log.add("setBg(${"%08X".format(color)})") }
        override fun pollTouches(): List<com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral.TouchEvent> = emptyList()
    }

    @Test
    fun `peripheral_find returns null when no peripheral matches`() {
        val out = CapturingOut()
        val env = FakeEnv(mount(), "", out, finder = { null })
        val r = host().eval("""print(peripheral.find("monitor") == null);""", "p.js", env)
        r.ok shouldBe true
        out.lines shouldBe listOf("true")
    }

    @Test
    fun `peripheral_find returns a usable monitor handle`() {
        val out = CapturingOut()
        val mon = FakeMonitor()
        val env = FakeEnv(mount(), "", out, finder = { kind -> if (kind == "monitor") mon else null })
        val r = host().eval("""
            var m = peripheral.find("monitor");
            m.write("hello");
            m.setCursorPos(3, 5);
            m.clear();
            var sz = m.getSize();
            print(sz[0] + " " + sz[1]);
        """.trimIndent(), "p.js", env)
        r.ok shouldBe true
        mon.log shouldBe listOf("write(hello)", "setCursorPos(3,5)", "clear()")
        out.lines shouldBe listOf("20 10")
    }
}
