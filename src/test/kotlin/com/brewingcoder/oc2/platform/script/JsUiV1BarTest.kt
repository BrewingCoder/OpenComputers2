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
 * Unit tests for `ui_v1`'s Bar widget (JS). Mirror of [LuaUiV1BarTest]
 * to guarantee API symmetry across script languages.
 */
class JsUiV1BarTest {

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
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Bar({});
            print(b.kind + " " + b.x + " " + b.y + " " + b.width + " " + b.height);
            print(b.value + " " + b.min + " " + b.max);
            print(b.color + " " + b.bg + " " + b.border + " " + b.markerColor);
            print(b.orientation + " " + b.showPct + " " + b.visible + " " + b.marker);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Bar 0 0 0 0",
            "0 0 100",
            "good bgCard edge fg",
            "h false true null",
        )
    }

    @Test
    fun `Bar ctor applies props`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Bar({
                x:10, y:20, width:100, height:16,
                value:75, min:0, max:100,
                color:"warn", bg:"bgCard", border:"edge",
                marker:50, markerColor:"fg",
                orientation:"v", showPct:true, visible:false
            });
            print(b.x + " " + b.y + " " + b.width + " " + b.height);
            print(b.value + " " + b.color + " " + b.bg + " " + b.marker + " " + b.markerColor);
            print(b.orientation + " " + b.showPct + " " + b.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "10 20 100 16",
            "75 warn bgCard 50 fg",
            "v true false",
        )
    }

    @Test
    fun `Bar set and get`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Bar({ value: 10 });
            b.set({ value: 55, color: "bad" });
            print(b.get("value") + " " + b.get("color"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("55 bad")
    }

    @Test
    fun `horizontal Bar fills left-to-right proportional to value`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:10, y:20, width:100, height:16, value:50 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls[0] shouldBe "drawRect(10,20,100,16,0xFF121827)"
        monitor.calls[1] shouldBe "drawRect(10,20,50,16,0xFF2ECC71)"
        monitor.calls[2] shouldBe "drawRectOutline(10,20,100,16,0xFF2E3A4E,t=1)"
    }

    @Test
    fun `vertical Bar fills bottom-up proportional to value`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:10, y:20, width:16, height:100, value:25, orientation:"v" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(10,95,16,25,0xFF2ECC71)"
    }

    @Test
    fun `Bar clamps value below min to 0 fill`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:16, value:-20, min:0, max:100 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.contains("0xFF2ECC71") } shouldBe true
    }

    @Test
    fun `Bar clamps value above max to full fill`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:16, value:500, min:0, max:100 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,100,16,0xFF2ECC71)"
    }

    @Test
    fun `Bar marker draws a line at the marker position`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:16, value:20, marker:50, markerColor:"hi" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawLine(50,0->50,15,0xFFE6F0FF)"
    }

    @Test
    fun `Bar showPct draws percent label in pixel space centered in bar`() {
        // Mirror of Lua. "42%" = 5x7 glyphs, 1-px gap -> 17 px wide.
        // Bar 0,0,96,12 -> cx=48, cy=6. x0=40, y0=3. '4' row0 setPixel(43,3).
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:96, height:12, value:42, showPct:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setPixel(43,3,0xFFFFFFFF)"
        monitor.calls.none { it.startsWith("write(") } shouldBe true
        monitor.calls.none { it.startsWith("setCursorPos(") } shouldBe true
    }

    @Test
    fun `Bar showPct label is vertically centered in a tall bar`() {
        // Mirror of Lua. Bar y=128, h=16 -> cx=48, cy=136, x0=40, y0=133.
        // '4' occupies y=133..139 inside bar 128..143 -> 5 above, 4 below.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:128, width:96, height:16, value:42, showPct:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setPixel(43,133,0xFFFFFFFF)"
        monitor.calls shouldContain "setPixel(40,137,0xFFFFFFFF)"
        monitor.calls shouldContain "setPixel(44,137,0xFFFFFFFF)"
    }

    @Test
    fun `Bar visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:16, value:50, visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Bar hex color overrides theme`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:16, value:50, color:0xFFABCDEF|0 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,50,16,0xFFABCDEF)"
    }

    @Test
    fun `Bar with zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:0, height:16, value:50 }));
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:0, value:50 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Bar with equal min and max treats range as 1 (value snaps to full)`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Bar({ x:0, y:0, width:100, height:16, value:5, min:5, max:5 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("drawRect(0,0,100,16,0xFF121827)") } shouldBe true
        monitor.calls.any { it.startsWith("drawRectOutline") } shouldBe true
    }
}
