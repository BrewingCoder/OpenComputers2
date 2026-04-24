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
 * Unit tests for `ui_v1`'s Banner widget (JS). Mirror of [LuaUiV1BannerTest].
 */
class JsUiV1BannerTest {

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
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Banner({});
            print(b.kind + " " + b.x + " " + b.y + " " + b.width + " " + b.height);
            print(b.text + "|" + b.style + "|" + b.color);
            print(b.textColor + " " + b.bg + " " + b.edgeAccent + " " + b.padding);
            print(b.align + " " + b.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Banner 0 0 0 0",
            "|info|null",
            "fg bgCard 4 4",
            "left true",
        )
    }

    @Test
    fun `Banner good style draws green accent`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:120, height:16, style:"good", text:"OK" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,120,12,0xFF121827)"
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFF2ECC71)"
        monitor.calls shouldContain "setCursorPos(1,0)"
        monitor.calls shouldContain "write(OK)"
    }

    @Test
    fun `Banner warn bad info none map to their tokens`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var m = peripheral.find("monitor");
            ui.mount(m, ui.Banner({ x:0, y:0,  width:80, height:16, style:"warn", text:"" }));
            ui.mount(m, ui.Banner({ x:0, y:20, width:80, height:16, style:"bad",  text:"" }));
            ui.mount(m, ui.Banner({ x:0, y:40, width:80, height:16, style:"info", text:"" }));
            ui.mount(m, ui.Banner({ x:0, y:60, width:80, height:16, style:"none", text:"" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFFF1C40F)"
        monitor.calls shouldContain "drawRect(0,24,4,12,0xFFE74C3C)"
        monitor.calls shouldContain "drawRect(0,48,4,12,0xFF3498DB)"
        monitor.calls shouldContain "drawRect(0,60,4,12,0xFF2E3A4E)"
    }

    @Test
    fun `Banner color override beats style mapping`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:80, height:16,
                                 style:"good", color:0xFFABCDEF|0, text:"" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,4,12,0xFFABCDEF)"
    }

    @Test
    fun `Banner center align centers text within text area`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:120, height:16,
                                 style:"info", text:"HI", align:"center" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(7,0)"
    }

    @Test
    fun `Banner right align right-aligns text at trailing padding`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:120, height:16,
                                 style:"info", text:"HI", align:"right" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(13,0)"
    }

    @Test
    fun `Banner empty text skips text calls but still draws bg and accent`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:80, height:16, style:"info" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
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
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:80, height:16,
                                 style:"good", edgeAccent:0, text:"" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF121827)"
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1
    }

    @Test
    fun `Banner visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:0, width:80, height:16,
                                 style:"good", text:"HIDDEN", visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
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
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Banner({ x:0, y:8, width:120, height:16, style:"info", text:"OK" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(1,1)"
    }

    @Test
    fun `Banner set and get`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Banner({ style:"info", text:"OK" });
            b.set({ style:"bad", text:"FAIL" });
            print(b.get("style") + " " + b.get("text"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("bad FAIL")
    }
}
