package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CobaltLuaHostTest {

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

    private fun host() = CobaltLuaHost()
    private fun mount(cap: Long = 4096): WritableMount = InMemoryMount(cap)

    /** Test helper preserving the pre-ScriptEnv call shape — wraps args in a [FakeEnv]. */
    private fun CobaltLuaHost.eval(source: String, chunkName: String, out: ShellOutput): ScriptResult =
        eval(source, chunkName, FakeEnv(mount(), "", out))

    /** Test helper for tests that need a specific mount + cwd. */
    private fun CobaltLuaHost.eval(source: String, chunkName: String, out: ShellOutput, m: WritableMount, cwd: String): ScriptResult =
        eval(source, chunkName, FakeEnv(m, cwd, out))

    @Test
    fun `print hello world`() {
        val out = CapturingOut()
        val r = host().eval("""print("hello world")""", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("hello world")
    }

    @Test
    fun `arithmetic and integer formatting`() {
        val out = CapturingOut()
        val r = host().eval("print(1 + 1)", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("2")  // not "2.0"
    }

    @Test
    fun `print of multiple args is tab-separated`() {
        val out = CapturingOut()
        val r = host().eval("""print("a", "b", "c")""", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("a\tb\tc")
    }

    @Test
    fun `for-loop emits one line per iteration — Scott's smoke test`() {
        val out = CapturingOut()
        val r = host().eval("for i = 1, 10 do print(i) end", "loop.lua", out)
        r.ok shouldBe true
        out.lines shouldBe (1..10).map { it.toString() }
    }

    @Test
    fun `nil and booleans render via tostring conventions`() {
        val out = CapturingOut()
        val r = host().eval("print(nil) print(true) print(false)", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("nil", "true", "false")
    }

    @Test
    fun `compile error is captured cleanly`() {
        val out = CapturingOut()
        val r = host().eval("for i = 1 do", "broken.lua", out)
        r.ok shouldBe false
        r.errorMessage!!.shouldContain("compile error")
        out.lines shouldBe emptyList()
    }

    @Test
    fun `runtime lua error is captured cleanly`() {
        val out = CapturingOut()
        val r = host().eval("error('explicit')", "test.lua", out)
        r.ok shouldBe false
        r.errorMessage!!.shouldContain("explicit")
    }

    @Test
    fun `string concatenation works through standard libs`() {
        val out = CapturingOut()
        val r = host().eval("""print("hello, " .. "world")""", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("hello, world")
    }

    @Test
    fun `each eval is isolated — globals do NOT persist across calls`() {
        val h = host()
        val out1 = CapturingOut()
        h.eval("x = 42", "a.lua", out1).ok shouldBe true

        val out2 = CapturingOut()
        // x should be undefined (nil) in the second call — fresh state
        h.eval("print(x)", "b.lua", out2).ok shouldBe true
        out2.lines shouldBe listOf("nil")
    }

    @Test
    fun `math library is available`() {
        val out = CapturingOut()
        val r = host().eval("print(math.floor(3.7))", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("3")
    }

    @Test
    fun `string library is available`() {
        val out = CapturingOut()
        val r = host().eval("""print(string.upper("hi"))""", "test.lua", out)
        r.ok shouldBe true
        out.lines shouldBe listOf("HI")
    }

    // ---------- fs API coverage ----------

    @Test
    fun `fs_write then fs_read round-trips`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("hi.txt", "hello")
            print(fs.read("hi.txt"))
        """.trimIndent(), "rw.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("hello")
    }

    @Test
    fun `fs_list returns directory entries`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("a.txt", "1")
            fs.write("b.txt", "2")
            local entries = fs.list("")
            table.sort(entries)
            for _, e in ipairs(entries) do print(e) end
        """.trimIndent(), "list.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("a.txt", "b.txt")
    }

    @Test
    fun `fs_exists and fs_isDir behave correctly`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.mkdir("things")
            fs.write("things/file.txt", "x")
            print(fs.exists("things"))         -- true
            print(fs.exists("nope"))           -- false
            print(fs.isDir("things"))          -- true
            print(fs.isDir("things/file.txt")) -- false
        """.trimIndent(), "probe.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("true", "false", "true", "false")
    }

    @Test
    fun `fs_size reports byte length`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("f.txt", "hello!")
            print(fs.size("f.txt"))
        """.trimIndent(), "size.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("6")
    }

    @Test
    fun `fs_append extends existing file`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("log.txt", "line 1")
            fs.append("log.txt", "-line 2")
            print(fs.read("log.txt"))
        """.trimIndent(), "append.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("line 1-line 2")
    }

    @Test
    fun `fs_delete removes a file`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            fs.write("doomed", "bye")
            print(fs.exists("doomed"))  -- true
            fs.delete("doomed")
            print(fs.exists("doomed"))  -- false
        """.trimIndent(), "delete.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("true", "false")
    }

    @Test
    fun `fs paths are resolved against the script's cwd`() {
        val out = CapturingOut()
        val m = mount()
        // Pre-create a subdir + file at /sub/hi.txt
        m.makeDirectory("sub")
        m.openForWrite("sub/hi.txt").use {
            val buf = java.nio.ByteBuffer.wrap("from-cwd".toByteArray())
            while (buf.hasRemaining()) it.write(buf)
        }
        // Run with cwd=sub; script should see hi.txt without a path prefix
        val r = host().eval("""print(fs.read("hi.txt"))""", "cwd.lua", out, m, "sub")
        r.ok shouldBe true
        out.lines shouldBe listOf("from-cwd")
    }

    @Test
    fun `fs_read on missing file raises a Lua error caught by pcall`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            local ok, err = pcall(fs.read, "nope")
            print(ok, err)
        """.trimIndent(), "missing.lua", out, m, "")
        r.ok shouldBe true
        out.lines.size shouldBe 1
        out.lines[0].startsWith("false") shouldBe true
        out.lines[0].shouldContain("no such file")
    }

    @Test
    fun `fs_capacity and fs_free return bytes`() {
        val out = CapturingOut()
        val m = InMemoryMount(capacityBytes = 1024L)
        val r = host().eval("""
            print(fs.capacity())
            fs.write("x.txt", "XXXXXXXX")  -- 8 bytes
            print(fs.free())
        """.trimIndent(), "cap.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("1024", "1016")
    }

    @Test
    fun `fs cannot path-traverse out of root`() {
        val out = CapturingOut()
        val m = mount()
        val r = host().eval("""
            local ok, err = pcall(fs.write, "../escape", "x")
            print(ok)
        """.trimIndent(), "traverse.lua", out, m, "")
        r.ok shouldBe true
        out.lines shouldBe listOf("false")
    }

    // ---------- peripheral API ----------

    /** Minimal in-memory monitor for binding tests. */
    private class FakeMonitor(private val cols: Int = 20, private val rows: Int = 10) :
        com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral {
        val log = mutableListOf<String>()
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        override fun write(text: String) { log.add("write($text)") }
        override fun setCursorPos(col: Int, row: Int) { log.add("setCursorPos($col,$row)") }
        override fun clear() { log.add("clear()") }
        override fun getSize(): Pair<Int, Int> = cols to rows
        override fun getCursorPos(): Pair<Int, Int> = 0 to 0
        override fun setForegroundColor(color: Int) { log.add("setFg(${"%08X".format(color)})") }
        override fun setBackgroundColor(color: Int) { log.add("setBg(${"%08X".format(color)})") }
        override fun pollTouches(): List<com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral.TouchEvent> = emptyList()
        override fun getPixelSize(): Pair<Int, Int> = (cols * 12) to (rows * 12)
        override fun clearPixels(argb: Int) { log.add("clearPixels(${"%08X".format(argb)})") }
        override fun setPixel(x: Int, y: Int, argb: Int) { log.add("setPixel($x,$y,${"%08X".format(argb)})") }
        override fun drawRect(x: Int, y: Int, w: Int, h: Int, argb: Int) { log.add("drawRect($x,$y,$w,$h)") }
        override fun drawRectOutline(x: Int, y: Int, w: Int, h: Int, argb: Int, thickness: Int) { log.add("drawRectOutline($x,$y,$w,$h,t=$thickness)") }
        override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) { log.add("drawLine($x1,$y1,$x2,$y2)") }
        override fun drawGradientV(x: Int, y: Int, w: Int, h: Int, topArgb: Int, bottomArgb: Int) { log.add("drawGradientV($x,$y,$w,$h)") }
        override fun fillCircle(cx: Int, cy: Int, r: Int, argb: Int) { log.add("fillCircle($cx,$cy,$r)") }
        override fun fillEllipse(cx: Int, cy: Int, rx: Int, ry: Int, argb: Int) { log.add("fillEllipse($cx,$cy,$rx,$ry)") }
        override fun drawArc(cx: Int, cy: Int, rx: Int, ry: Int, thickness: Int, startDeg: Int, sweepDeg: Int, argb: Int) {
            log.add("drawArc($cx,$cy,$rx,$ry,t=$thickness,$startDeg..${startDeg + sweepDeg})")
        }
        override fun drawItem(x: Int, y: Int, wPx: Int, hPx: Int, itemId: String) { log.add("drawItem($x,$y,$wPx,$hPx,$itemId)") }
        override fun drawFluid(x: Int, y: Int, wPx: Int, hPx: Int, fluidId: String) { log.add("drawFluid($x,$y,$wPx,$hPx,$fluidId)") }
        override fun drawChemical(x: Int, y: Int, wPx: Int, hPx: Int, chemicalId: String) { log.add("drawChemical($x,$y,$wPx,$hPx,$chemicalId)") }
        override fun clearIcons() { log.add("clearIcons()") }
    }

    @Test
    fun `peripheral_find returns nil when no peripheral on channel`() {
        val out = CapturingOut()
        val env = FakeEnv(mount(), "", out, finder = { null })
        val r = host().eval("""print(peripheral.find("monitor") == nil)""", "p.lua", env)
        r.ok shouldBe true
        out.lines shouldBe listOf("true")
    }

    @Test
    fun `sleep treats argument as seconds not milliseconds`() {
        // sleep(0.05) should take ~50 ms. If it were treating the argument as ms it would
        // take only 0.05 ms (essentially instant). We verify elapsed time >= 40 ms.
        val out = CapturingOut()
        val start = System.currentTimeMillis()
        val r = host().eval("sleep(0.05)", "sleep_test.lua", out)
        val elapsed = System.currentTimeMillis() - start
        r.ok shouldBe true
        (elapsed >= 40) shouldBe true  // at least 40 ms elapsed — not instant
    }

    @Test
    fun `peripheral_find returns a usable monitor handle`() {
        val out = CapturingOut()
        val mon = FakeMonitor()
        val env = FakeEnv(mount(), "", out, finder = { kind -> if (kind == "monitor") mon else null })
        val r = host().eval("""
            local m = peripheral.find("monitor")
            m.write("hello")
            m.setCursorPos(3, 5)
            m.clear()
            local w, h = m.getSize()
            print(w, h)
        """.trimIndent(), "p.lua", env)
        r.ok shouldBe true
        mon.log shouldBe listOf("write(hello)", "setCursorPos(3,5)", "clear()")
        out.lines shouldBe listOf("20\t10")
    }

    @Test
    fun `script args are forwarded as varargs and on arg table`() {
        val out = CapturingOut()
        val r = host().eval(
            source = "local a = {...}; print(#a, a[1], a[2], arg[1], arg[2])",
            chunkName = "args.lua",
            env = FakeEnv(mount(), "", out),
            scriptArgs = listOf("minecraft:raw_iron", "64"),
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("2\tminecraft:raw_iron\t64\tminecraft:raw_iron\t64")
    }

    @Test
    fun `empty script args produces empty varargs and no arg global`() {
        val out = CapturingOut()
        val r = host().eval(
            source = "local a = {...}; print(#a, type(arg))",
            chunkName = "args.lua",
            env = FakeEnv(mount(), "", out),
            scriptArgs = emptyList(),
        )
        r.ok shouldBe true
        // No args → `arg` global stays nil so existing scripts that don't use args see no surprise.
        out.lines shouldBe listOf("0\tnil")
    }
}
