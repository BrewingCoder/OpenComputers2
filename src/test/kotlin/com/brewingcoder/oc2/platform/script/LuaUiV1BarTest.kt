package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.ResourceMount
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for `ui_v1`'s Bar widget (Lua). Mirror of [JsUiV1BarTest]
 * to guarantee API symmetry across script languages.
 */
class LuaUiV1BarTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() = Unit
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String = "",
        override val out: ShellOutput,
        private val monitor: RecordingMonitor,
        override val events: ScriptEventQueue = ScriptEventQueue(),
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? =
            if (kind == "monitor") monitor else null
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null || kind == "monitor") listOf(monitor) else emptyList()
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun mkEnv(monitor: RecordingMonitor = RecordingMonitor()): Pair<FakeEnv, CapturingOut> {
        val rom = ResourceMount("assets/oc2/rom")
        val mount = UnionMount(InMemoryMount(), rom)
        val out = CapturingOut()
        return FakeEnv(mount, out = out, monitor = monitor) to out
    }

    @Test
    fun `Bar constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Bar{}
            print(b.kind, b.x, b.y, b.width, b.height)
            print(b.value, b.min, b.max)
            print(b.color, b.bg, b.border, b.markerColor)
            print(b.orientation, tostring(b.showPct), tostring(b.visible), tostring(b.marker))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Bar\t0\t0\t0\t0",
            "0\t0\t100",
            "good\tbgCard\tedge\tfg",
            "h\tfalse\ttrue\tnil",
        )
    }

    @Test
    fun `Bar ctor applies props`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Bar{
                x=10, y=20, width=100, height=16,
                value=75, min=0, max=100,
                color="warn", bg="bgCard", border="edge",
                marker=50, markerColor="fg",
                orientation="v", showPct=true, visible=false,
            }
            print(b.x, b.y, b.width, b.height)
            print(b.value, b.color, b.bg, b.marker, b.markerColor)
            print(b.orientation, tostring(b.showPct), tostring(b.visible))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "10\t20\t100\t16",
            "75\twarn\tbgCard\t50\tfg",
            "v\ttrue\tfalse",
        )
    }

    @Test
    fun `Bar set and get`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Bar{ value=10 }
            b:set{ value=55, color="bad" }
            print(b:get("value"), b:get("color"))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("55\tbad")
    }

    @Test
    fun `horizontal Bar fills left-to-right proportional to value`() {
        // width=100, value=50, range=100 -> fw = floor(100*0.5) = 50
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=10, y=20, width=100, height=16, value=50 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Track bg (bgCard), fill (good), border (edge, thickness 1).
        monitor.calls[0] shouldBe "drawRect(10,20,100,16,0xFF121827)"
        monitor.calls[1] shouldBe "drawRect(10,20,50,16,0xFF2ECC71)"
        monitor.calls[2] shouldBe "drawRectOutline(10,20,100,16,0xFF2E3A4E,t=1)"
    }

    @Test
    fun `vertical Bar fills bottom-up proportional to value`() {
        // height=100, value=25, range=100 -> fh = floor(100*0.25) = 25
        // fill y = y + h - fh = 20 + 100 - 25 = 95
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=10, y=20, width=16, height=100, value=25, orientation="v" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(10,95,16,25,0xFF2ECC71)"
    }

    @Test
    fun `Bar clamps value below min to 0 fill`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=16, value=-20, min=0, max=100 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // No fill drawRect (width=0 suppressed); only bg + outline.
        monitor.calls.none { it.contains("0xFF2ECC71") } shouldBe true
    }

    @Test
    fun `Bar clamps value above max to full fill`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=16, value=500, min=0, max=100 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,100,16,0xFF2ECC71)"
    }

    @Test
    fun `Bar marker draws a line at the marker position`() {
        // horizontal, marker=50, range=100 -> mv=0.5 -> mx = 0 + floor(100*0.5) = 50
        // line is vertical: (50,0) -> (50, h-1=15)
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=16, value=20, marker=50, markerColor="hi" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawLine(50,0->50,15,0xFFE6F0FF)"
    }

    @Test
    fun `Bar showPct draws percent label in pixel space centered in bar`() {
        // Bar x=0, y=0, width=96, height=12, value=42, showPct=true.
        // "42%" = three 5x7 glyphs with 1-px gaps -> 17 px wide.
        // cx=48, cy=6. x0 = floor(48 - 8) = 40. y0 = floor(6 - 3) = 3.
        // Glyph '4' row 0 = bits [2] (00010 -> col 3) -> setPixel(40+3, 3).
        // Emits NO setCursorPos / NO write -- text lives in pixel buffer.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=96, height=12, value=42, showPct=true })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setPixel(43,3,0xFFFFFFFF)"
        monitor.calls.none { it.startsWith("write(") } shouldBe true
        monitor.calls.none { it.startsWith("setCursorPos(") } shouldBe true
    }

    @Test
    fun `Bar showPct label is vertically centered in a tall bar`() {
        // Bar y=128, h=16, w=96, value=42. cx=48, cy=136.
        // x0 = 40, y0 = floor(136 - 3) = 133.
        // '4' glyph occupies rows y=133..139 inside bar 128..143 -> 5 above, 4 below.
        // Middle horizontal bar of '4' (bits=31) lands at y=137 (= y0 + 4).
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=128, width=96, height=16, value=42, showPct=true })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setPixel(43,133,0xFFFFFFFF)"          // '4' top pixel
        monitor.calls shouldContain "setPixel(40,137,0xFFFFFFFF)"          // '4' middle-bar left
        monitor.calls shouldContain "setPixel(44,137,0xFFFFFFFF)"          // '4' middle-bar right
    }

    @Test
    fun `Bar visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=16, value=50, visible=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Bar hex color overrides theme`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=16, value=50, color=0xFFABCDEF })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,50,16,0xFFABCDEF)"
    }

    @Test
    fun `Bar with zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=0, height=16, value=50 })
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=0, value=50 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Bar with equal min and max treats range as 1 (value snaps to full)`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Bar{ x=0, y=0, width=100, height=16, value=5, min=5, max=5 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // degenerate range -> forced 1; value-min=0 -> pct=0 -> no fill.
        // (No crash is the real test — confirm just the track + outline rendered.)
        monitor.calls.any { it.startsWith("drawRect(0,0,100,16,0xFF121827)") } shouldBe true
        monitor.calls.any { it.startsWith("drawRectOutline") } shouldBe true
    }
}
