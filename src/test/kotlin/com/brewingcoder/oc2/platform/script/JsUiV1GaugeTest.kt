package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.ResourceMount
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for `ui_v1`'s Gauge widget (JS). Mirror of [LuaUiV1GaugeTest]
 * to guarantee API symmetry across script languages.
 */
class JsUiV1GaugeTest {

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
    fun `Gauge constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var g = ui.Gauge({});
            print(g.kind + " " + g.x + " " + g.y + " " + g.width + " " + g.height);
            print(g.value + " " + g.min + " " + g.max);
            print(g.color + " " + g.bg + " " + g.thickness);
            print(g.startDeg + " " + g.sweepDeg + " " + g.showValue + " " + g.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Gauge 0 0 0 0",
            "0 0 100",
            "good bgCard 6",
            "225 270 false true",
        )
    }

    @Test
    fun `Gauge ctor applies props`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var g = ui.Gauge({
                x:5, y:10, width:80, height:80,
                value:65, min:0, max:100,
                color:"bad", bg:"edge", thickness:10,
                startDeg:180, sweepDeg:180,
                label:"RPM", labelColor:"hi", showValue:true,
                visible:false
            });
            print(g.x + " " + g.y + " " + g.width + " " + g.height);
            print(g.value + " " + g.color + " " + g.bg + " " + g.thickness);
            print(g.startDeg + " " + g.sweepDeg + " " + g.label + " " + g.labelColor);
            print(g.showValue + " " + g.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "5 10 80 80",
            "65 bad edge 10",
            "180 180 RPM hi",
            "true false",
        )
    }

    @Test
    fun `Gauge set and get`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var g = ui.Gauge({ value: 10 });
            g.set({ value: 88, color: "warn" });
            print(g.get("value") + " " + g.get("color"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("88 warn")
    }

    @Test
    fun `Gauge at value=0 draws only bg arc, not fill`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:0 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawArc(") } shouldBe 1
        monitor.calls.none { it.contains("0xFF2ECC71") } shouldBe true
    }

    @Test
    fun `Gauge at value=max draws only fill arc`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:100 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawArc(") } shouldBe 1
        monitor.calls.any { it.contains("0xFF2ECC71") } shouldBe true
        monitor.calls.none { it.contains("0xFF121827") } shouldBe true
    }

    @Test
    fun `Gauge at mid value draws both arcs`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:50 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawArc(") } shouldBe 2
        monitor.calls.any { it.contains("0xFF2ECC71") } shouldBe true
        monitor.calls.any { it.contains("0xFF121827") } shouldBe true
    }

    @Test
    fun `Gauge clamps below min`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:-50, min:0, max:100 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.contains("0xFF2ECC71") } shouldBe true
    }

    @Test
    fun `Gauge clamps above max`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:999, min:0, max:100 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawArc(") } shouldBe 1
        monitor.calls.any { it.contains("0xFF2ECC71") } shouldBe true
    }

    @Test
    fun `Gauge showValue draws numeric readout through drawText`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:42, showValue:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("write(42)") } shouldBe true
    }

    @Test
    fun `Gauge label draws through drawText`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:42, label:"TEMP" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("write(TEMP)") } shouldBe true
    }

    @Test
    fun `Gauge showValue wins over label`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:42,
                                showValue:true, label:"IGNORED" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.contains("IGNORED") } shouldBe true
    }

    @Test
    fun `Gauge visible=false emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:50, visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Gauge with zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:0, height:100, value:50 }));
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:0, value:50 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Gauge hex color overrides theme`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100, value:100, color: (0xFFABCDEF | 0) }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.contains("0xFFABCDEF") } shouldBe true
    }

    @Test
    fun `Gauge full circle 360 with pct=0_5 draws both halves`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Gauge({ x:0, y:0, width:100, height:100,
                                startDeg:0, sweepDeg:360, value:50 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawArc(") } shouldBe 2
    }
}
