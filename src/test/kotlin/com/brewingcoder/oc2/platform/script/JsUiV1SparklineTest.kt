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
 * Unit tests for `ui_v1`'s Sparkline widget (JS). Mirror of [LuaUiV1SparklineTest].
 */
class JsUiV1SparklineTest {

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
    fun `Sparkline constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Sparkline({});
            print(s.kind + " " + s.x + " " + s.y + " " + s.width + " " + s.height);
            print(s.capacity + " " + s.min + " " + s.max);
            print(s.color + " " + s.bg + " " + s.border);
            print(s.baseline + " " + s.baselineColor);
            print(s.fill + " " + s.showLast + " " + s.visible);
            print("values=" + s.values.length);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Sparkline 0 0 0 0",
            "64 null null",
            "info bgCard edge",
            "null muted",
            "false false true",
            "values=0",
        )
    }

    @Test
    fun `Sparkline ctor applies props`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Sparkline({
                x:5, y:10, width:100, height:40,
                capacity:8, min:0, max:100,
                color:"warn", bg:"edge", border:"bg",
                baseline:50, baselineColor:"hi",
                fill:true, fillColor:"bad", showLast:true,
                values:[1,2,3]
            });
            print(s.x + " " + s.y + " " + s.width + " " + s.height);
            print(s.capacity + " " + s.min + " " + s.max);
            print(s.color + " " + s.bg + " " + s.border);
            print(s.baseline + " " + s.baselineColor);
            print(s.fill + " " + s.fillColor + " " + s.showLast);
            print("values=" + s.values.length + " first=" + s.values[0] + " last=" + s.values[2]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "5 10 100 40",
            "8 0 100",
            "warn edge bg",
            "50 hi",
            "true bad true",
            "values=3 first=1 last=3",
        )
    }

    @Test
    fun `Sparkline push appends to ring`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Sparkline({ capacity: 10 });
            s.push(1); s.push(2); s.push(3);
            print(s.values.length + " " + s.values[0] + " " + s.values[2]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("3 1 3")
    }

    @Test
    fun `Sparkline push rings off head when over capacity`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Sparkline({ capacity: 3 });
            s.push(1); s.push(2); s.push(3); s.push(4); s.push(5);
            print(s.values.length + " " + s.values[0] + " " + s.values[1] + " " + s.values[2]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("3 3 4 5")
    }

    @Test
    fun `Sparkline clear empties the ring`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Sparkline({ values:[1,2,3] });
            s.clear();
            print(s.values.length);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("0")
    }

    @Test
    fun `Sparkline setValues replaces series`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Sparkline({ values:[1,2,3] });
            s.setValues([10, 20]);
            print(s.values.length + " " + s.values[0] + " " + s.values[1]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("2 10 20")
    }

    @Test
    fun `Sparkline ctor defensive copy does not alias input array`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var src = [1,2,3];
            var s = ui.Sparkline({ values: src });
            src[0] = 999;
            print(s.values[0]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("1")
    }

    @Test
    fun `Sparkline with no values draws bg and border only`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.startsWith("drawLine(") } shouldBe true
        monitor.calls.any { it.startsWith("drawRect(") } shouldBe true
        monitor.calls.any { it.startsWith("drawRectOutline(") } shouldBe true
    }

    @Test
    fun `Sparkline with two values draws at least one drawLine`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40, values:[10, 20] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawLine(") } shouldBe 1
    }

    @Test
    fun `Sparkline with single value draws a dot via setPixel`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40, values:[42] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("setPixel(") } shouldBe true
        monitor.calls.none { it.startsWith("drawLine(") } shouldBe true
    }

    @Test
    fun `Sparkline fill adds vertical drawLines under every sample`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40,
                                    values:[10, 20, 30], fill:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawLine(") } shouldBe 5
    }

    @Test
    fun `Sparkline baseline draws horizontal line regardless of data`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40,
                                    baseline:50, min:0, max:100 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawLine(") } shouldBe 1
    }

    @Test
    fun `Sparkline showLast writes numeric readout through drawText`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40,
                                    values:[10, 20, 42], showLast:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("write(42)") } shouldBe true
    }

    @Test
    fun `Sparkline visible=false emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:40,
                                    values:[1,2,3], visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `Sparkline zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:0, height:40, values:[1,2] }));
            ui.mount(peripheral.find("monitor"),
                     ui.Sparkline({ x:0, y:0, width:100, height:0, values:[1,2] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }
}
