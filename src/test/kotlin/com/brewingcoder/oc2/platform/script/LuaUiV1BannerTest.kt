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
 * Unit tests for `ui_v1`'s Banner widget (Lua). Mirror of [JsUiV1BannerTest].
 */
class LuaUiV1BannerTest {

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
    fun `Banner constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Banner{}
            print(b.kind, b.x, b.y, b.width, b.height)
            print(tostring(b.text), b.style, tostring(b.color))
            print(b.textColor, b.bg, b.edgeAccent, b.padding)
            print(b.align, tostring(b.visible))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Banner\t0\t0\t0\t0",
            "\tinfo\tnil",
            "fg\tbgCard\t4\t4",
            "left\ttrue",
        )
    }

    @Test
    fun `Banner good style draws green accent`() {
        // RecordingMonitor defaults: pixel=320x240, text=40x20 -> pxPerCol=8, pxPerRow=12.
        // x=0, y=0, width=120, height=16, style="good", text="OK"
        // cellCount = floor(16/12) = 1 (odd). snappedH = 12. bannerCy = 8.
        // topCellRow = floor(8/12) - 0 = 0. snappedY = 0. textRow = 0.
        // bg rect: 0,0,120,12 bgCard=0xFF121827 (snapped from 16 to 12)
        // accent rect: 0,0,4,12 good=0xFF2ECC71
        // textLeftPx = 0 + 4 + 4 = 8, textCol = ceil(8/8) = 1
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=120, height=16, style="good", text="OK" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,120,12,0xFF121827)"
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFF2ECC71)"
        monitor.calls shouldContain "setCursorPos(1,0)"
        monitor.calls shouldContain "write(OK)"
    }

    @Test
    fun `Banner warn bad info none map to their tokens`() {
        // h=16, pxPerRow=12 -> cellCount=1, snappedH=12. y snaps to cell row:
        //   y=0  -> bannerCy=8,  topCellRow=0, snappedY=0
        //   y=20 -> bannerCy=28, topCellRow=2, snappedY=24
        //   y=40 -> bannerCy=48, topCellRow=4, snappedY=48
        //   y=60 -> bannerCy=68, topCellRow=5, snappedY=60
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local m = peripheral.find("monitor")
            ui.mount(m, ui.Banner{ x=0, y=0,   width=80, height=16, style="warn", text="" })
            ui.mount(m, ui.Banner{ x=0, y=20,  width=80, height=16, style="bad",  text="" })
            ui.mount(m, ui.Banner{ x=0, y=40,  width=80, height=16, style="info", text="" })
            ui.mount(m, ui.Banner{ x=0, y=60,  width=80, height=16, style="none", text="" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFFF1C40F)"   // warn
        monitor.calls shouldContain "drawRect(0,24,4,12,0xFFE74C3C)"  // bad
        monitor.calls shouldContain "drawRect(0,48,4,12,0xFF3498DB)"  // info
        monitor.calls shouldContain "drawRect(0,60,4,12,0xFF2E3A4E)"  // none -> edge
    }

    @Test
    fun `Banner color override beats style mapping`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=80, height=16,
                                style="good", color=0xFFABCDEF, text="" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFFABCDEF)"
    }

    @Test
    fun `Banner center align centers text within text area`() {
        // w=120, edge=4, padding=4 -> textLeftPx=8, textRightPx=116. area=108.
        // "HI" (2 chars) * 8 px/col = 16 px. startPx = 8 + floor((108-16)/2) = 8+46 = 54.
        // textCol = ceil(54/8) = 7.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=120, height=16,
                                style="info", text="HI", align="center" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(7,0)"
    }

    @Test
    fun `Banner right align right-aligns text at trailing padding`() {
        // w=120, padding=4 -> textRightPx=116. "HI" = 16 px. startPx = 116-16 = 100.
        // textCol = ceil(100/8) = 13.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=120, height=16,
                                style="info", text="HI", align="right" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(13,0)"
    }

    @Test
    fun `Banner empty text skips text calls but still draws bg and accent`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=80, height=16, style="info" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF121827)"
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFF3498DB)"
        monitor.calls.none { it.startsWith("write(") } shouldBe true
        monitor.calls.none { it.startsWith("setCursorPos(") } shouldBe true
    }

    @Test
    fun `Banner edgeAccent=0 skips accent rect`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=80, height=16,
                                style="good", edgeAccent=0, text="" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF121827)"
        // Only one drawRect: the bg. No accent rect.
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1
    }

    @Test
    fun `Banner visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=0, width=80, height=16,
                                style="good", text="HIDDEN", visible=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Banner text row stays inside bg when y is not cell-aligned`() {
        // Regression: old formula (y + floor((h-pxPerRow)/2)) / pxPerRow placed
        // text at row 0 (px 0..12) for a banner at y=8, h=16 — floating ABOVE
        // the bg rect (8..24). New formula picks the cell containing banner
        // center: floor((8 + 8) / 12) = 1 → text at px 12..24, inside bg.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Banner{ x=0, y=8, width=120, height=16, style="info", text="OK" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(1,1)"
    }

    @Test
    fun `Banner set and get`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local b = ui.Banner{ style="info", text="OK" }
            b:set{ style="bad", text="FAIL" }
            print(b:get("style"), b:get("text"))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("bad\tFAIL")
    }
}
