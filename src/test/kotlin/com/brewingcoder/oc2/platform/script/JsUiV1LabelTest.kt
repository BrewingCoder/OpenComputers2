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
 * Unit tests for `ui_v1`'s Label widget (JS). Mirrors [LuaUiV1LabelTest]
 * to verify API symmetry across the two languages.
 */
class JsUiV1LabelTest {

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
    fun `ui_v1 loads via require`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            print(ui.VERSION);
            print(typeof ui.Label);
            print(typeof ui.mount);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("v1", "function", "function")
    }

    @Test
    fun `Label constructor applies props and exposes defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var lbl = ui.Label({ x:10, y:20, width:100, height:16, text:"Hi", color:"good" });
            print(lbl.kind + " " + lbl.x + " " + lbl.y + " " + lbl.width + " " + lbl.height);
            print(lbl.text + " " + lbl.color + " " + lbl.align + " " + lbl.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Label 10 20 100 16",
            "Hi good left true",
        )
    }

    @Test
    fun `Label set and get mutate and read props`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var lbl = ui.Label({ text: "old" });
            lbl.set({ text: "new", color: "warn" });
            print(lbl.get("text") + " " + lbl.get("color"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("new warn")
    }

    @Test
    fun `theme token resolves to palette color`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            function chk(got, want) { print(got === want ? "ok" : ("got " + got)); }
            chk(ui._resolveColor("good", 0),        0xFF2ECC71 | 0);
            chk(ui._resolveColor("hi", 0),          0xFFE6F0FF | 0);
            chk(ui._resolveColor(0xFF123456, 0),    0xFF123456 | 0);
            chk(ui._resolveColor("unknown", 0xDEADBEEF | 0), 0xDEADBEEF | 0);
            chk(ui._resolveColor(null, 0xCAFEBABE | 0), 0xCAFEBABE | 0);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("ok", "ok", "ok", "ok", "ok")
    }

    @Test
    fun `left-aligned Label writes text at its x cell`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            ui.mount(mon, ui.Label({ x:16, y:24, width:200, height:12, text:"Hello", align:"left" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly listOf(
            "setFg(0xFFFFFFFF)",
            "setBg(0x00000000)",
            "setCursorPos(2,2)",
            "write(Hello)",
        )
    }

    @Test
    fun `center-aligned Label offsets text by half the free width`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:0, y:0, width:96, height:12, text:"Hi", align:"center" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(5,0)"
        monitor.calls shouldContain "write(Hi)"
    }

    @Test
    fun `right-aligned Label flushes text to the right edge`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:0, y:0, width:80, height:12, text:"Hi", align:"right" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setCursorPos(8,0)"
    }

    @Test
    fun `Label with bg token draws a background rect first`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:10, y:10, width:50, height:12, text:"x", bg:"bgCard" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // y=10 isn't cell-aligned (pxPerRow=12); snapCellRect centers the one
        // available cell row on midY=16 -> snappedY=12, snappedH=12. Bg and
        // text co-register on cell row 1.
        monitor.calls[0] shouldBe "drawRect(10,12,50,12,0xFF121827)"
    }

    @Test
    fun `Label without bg prop does NOT draw a background rect`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:0, y:0, width:40, height:12, text:"x" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.startsWith("drawRect(") } shouldBe true
    }

    @Test
    fun `visible=false Label emits zero monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:0, y:0, width:40, height:12, text:"x", visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `hex color overrides theme lookup`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:0, y:0, width:40, height:12, text:"x", color: 0xFFABCDEF | 0 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setFg(0xFFABCDEF)"
    }

    @Test
    fun `mount then unmount clears per-monitor state`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            ui.mount(mon, ui.Label({ x:0, y:0, width:40, height:12, text:"A" }));
            ui.render();
            ui.unmount(mon);
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it == "write(A)" } shouldBe 1
    }

    @Test
    fun `multiple mounts render in insertion order`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            ui.mount(mon, ui.Label({ x:0, y:0, width:40, height:12, text:"first" }));
            ui.mount(mon, ui.Label({ x:0, y:0, width:40, height:12, text:"second" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val firstIdx = monitor.calls.indexOf("write(first)")
        val secondIdx = monitor.calls.indexOf("write(second)")
        (firstIdx >= 0 && secondIdx > firstIdx) shouldBe true
    }

    @Test
    fun `setTheme swaps the palette`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.setTheme({ fg: 0xFF111111 | 0, good: 0xFF222222 | 0 });
            ui.mount(peripheral.find("monitor"),
                     ui.Label({ x:0, y:0, width:40, height:12, text:"x", color:"fg" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "setFg(0xFF111111)"
    }
}
