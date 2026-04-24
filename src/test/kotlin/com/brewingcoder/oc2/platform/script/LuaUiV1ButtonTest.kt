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
 * Unit tests for `ui_v1`'s Button widget (Lua). Mirror of [JsUiV1ButtonTest].
 *
 * RecordingMonitor defaults: 320x240 px, 40x20 text -> pxPerCol=8, pxPerRow=12.
 * Color math for Button primary = 0xFF3498DB (R=52, G=152, B=219):
 *   lighten(+30) -> 0xFF52B6F9   (top-gradient highlight)
 *   lighten(+40) -> 0xFF5CC0FF   (border, B clamped from 259 to 255)
 *   dim          -> 0xFF1A4C6D   (enabled=false, halved)
 */
class LuaUiV1ButtonTest {

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
    fun `Button constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Button{}
            print(b.kind, b.x, b.y, b.width, b.height)
            print(tostring(b.label), b.style, tostring(b.onClick))
            print(tostring(b.enabled), tostring(b.visible), b.borderThickness)
            print(b.textColor, tostring(b.color), tostring(b.borderColor))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Button\t0\t0\t0\t0",
            "\tprimary\tnil",
            "true\ttrue\t2",
            "fg\tnil\tnil",
        )
    }

    @Test
    fun `Button primary style draws base + gradient + border + label`() {
        // w=120, h=16, pxPerRow=12 -> cellCount=1, snappedH=12, snappedY=0, textRow=0.
        // "GO" = 2 chars * 8 = 16 px. startPx = 0 + (120-16)/2 = 52. textCol = floor(52/8) = 6.
        // topHalf = floor(12/2) = 6.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=120, height=16, label="GO" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,120,12,0xFF3498DB)"
        monitor.calls shouldContain "drawGradientV(0,0,120,6,0xFF52B6F9->0xFF3498DB)"
        monitor.calls shouldContain "drawRectOutline(0,0,120,12,0xFF5CC0FF,t=2)"
        monitor.calls shouldContain "setCursorPos(6,0)"
        monitor.calls shouldContain "write(GO)"
    }

    @Test
    fun `Button ghost style uses ghost theme token`() {
        // ghost = 0xFF2E3A4E; lighten(+30) -> 0xFF4C586C; lighten(+40) -> 0xFF566276
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, label="", style="ghost" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF2E3A4E)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF566276,t=2)"
    }

    @Test
    fun `Button danger style uses danger theme token`() {
        // danger = 0xFFE74C3C; lighten(+40): R=231+40=271 clamp to 255, G=76+40=116=0x74, B=60+40=100=0x64 -> 0xFFFF7464
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, label="", style="danger" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFFE74C3C)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFFFF7464,t=2)"
    }

    @Test
    fun `Button color override beats style mapping`() {
        // color = 0xFF123456; lighten(+40): R=18+40=58=0x3A, G=52+40=92=0x5C, B=86+40=126=0x7E -> 0xFF3A5C7E
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, style="primary",
                                color=0xFF123456, label="" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF123456)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF3A5C7E,t=2)"
    }

    @Test
    fun `Button enabled=false dims base + border + text`() {
        // primary = 0xFF3498DB -> dim: R=52/2=26=0x1A, G=152/2=76=0x4C, B=219/2=109=0x6D -> 0xFF1A4C6D
        // fg = 0xFFFFFFFF -> dim: 0xFF7F7F7F
        // border = lighten(primary,40) = 0xFF5CC0FF -> dim: R=92/2=46=0x2E, G=192/2=96=0x60, B=255/2=127=0x7F -> 0xFF2E607F
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, label="X", enabled=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF1A4C6D)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF2E607F,t=2)"
        monitor.calls shouldContain "setFg(0xFF7F7F7F)"
    }

    @Test
    fun `Button borderThickness=0 skips outline`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, label="", borderThickness=0 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.startsWith("drawRectOutline(") } shouldBe true
    }

    @Test
    fun `Button empty label draws chrome and only blank-fills the text row`() {
        // Empty label still clears the text row's cell band (so a prior longer
        // label doesn't linger on the grid), but emits no glyph writes.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF3498DB)"
        monitor.calls.any { it.startsWith("drawGradientV(") } shouldBe true
        monitor.calls.any { it.startsWith("drawRectOutline(") } shouldBe true
        // Only the blanking write is allowed; no non-space payload.
        monitor.calls.filter { it.startsWith("write(") }
            .none { it.substring(6, it.length - 1).any { ch -> ch != ' ' } } shouldBe true
    }

    @Test
    fun `Button blanks its label cell-band before writing so a shrunk label does not leave stale glyphs`() {
        // Reproduces the POWER: ON/OFF bug: when the second label is shorter
        // than the first, the trailing cells must be overwritten with spaces.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local btn = ui.Button{ x=0, y=0, width=180, height=16, label="POWER: OFF" }
            ui.mount(peripheral.find("monitor"), btn)
            ui.render()
            btn:set{ label = "POWER: ON" }
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Button is 180px wide at 8px/col on a 40-col monitor => cells 0..22.
        // The second render must include a 23-space blanking write BEFORE "POWER: ON".
        val blanks = " ".repeat(23)
        monitor.calls shouldContain "write($blanks)"
        monitor.calls shouldContain "write(POWER: ON)"
    }

    @Test
    fun `Button visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, label="X", visible=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Button custom borderColor overrides the auto-lightened default`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=16, label="",
                                borderColor=0xFFFF00FF })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFFFF00FF,t=2)"
    }

    @Test
    fun `Button onClick fires on ui tick touch within bounds`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local clicks = 0
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=10, y=10, width=80, height=20, label="X",
                                onClick=function(e) clicks = clicks + 1 end })
            ui.tick({"monitor_touch", 5, 2, 40, 20, "scott"})
            print("clicks=" .. clicks)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=1")
    }

    @Test
    fun `Button onClick does not fire when touch is outside bounds`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local clicks = 0
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=10, y=10, width=80, height=20, label="X",
                                onClick=function(e) clicks = clicks + 1 end })
            -- touch at (1,1) -- outside the button's rect
            ui.tick({"monitor_touch", 0, 0, 1, 1, "scott"})
            print("clicks=" .. clicks)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=0")
    }

    @Test
    fun `Button enabled=false suppresses onClick dispatch`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local clicks = 0
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=10, y=10, width=80, height=20, label="X",
                                enabled=false,
                                onClick=function(e) clicks = clicks + 1 end })
            ui.tick({"monitor_touch", 5, 2, 40, 20, "scott"})
            print("clicks=" .. clicks)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=0")
    }

    @Test
    fun `Button visible=false suppresses onClick dispatch`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local clicks = 0
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=10, y=10, width=80, height=20, label="X",
                                visible=false,
                                onClick=function(e) clicks = clicks + 1 end })
            ui.tick({"monitor_touch", 5, 2, 40, 20, "scott"})
            print("clicks=" .. clicks)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=0")
    }

    @Test
    fun `Button event object carries widget + player + coords`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Button{ x=0, y=0, width=80, height=20, label="T",
                                onClick=function(e)
                                  print(e.type, e.widget.kind, e.player, e.col, e.row, e.px, e.py)
                                end })
            ui.tick({"monitor_touch", 3, 1, 24, 8, "alice"})
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("click\tButton\talice\t3\t1\t24\t8")
    }

    @Test
    fun `Button topmost-first routing beats overlapping earlier widgets`() {
        // Two overlapping buttons; the later-mounted one is on top and should win.
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local who = ""
            local m = peripheral.find("monitor")
            ui.mount(m, ui.Button{ x=0, y=0, width=80, height=20, label="A",
                                   onClick=function() who = "A" end })
            ui.mount(m, ui.Button{ x=0, y=0, width=80, height=20, label="B",
                                   onClick=function() who = "B" end })
            ui.tick({"monitor_touch", 1, 0, 8, 8, "scott"})
            print("who=" .. who)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("who=B")
    }

    @Test
    fun `Button set and get`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Button{ style="primary", label="OK" }
            b:set{ style="danger", label="STOP", enabled=false }
            print(b:get("style"), b:get("label"), tostring(b:get("enabled")))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("danger\tSTOP\tfalse")
    }
}
