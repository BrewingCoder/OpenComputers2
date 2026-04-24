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
 * Unit tests for `ui_v1`'s Indicator widget (JS). Mirror of [LuaUiV1IndicatorTest]
 * to guarantee API symmetry across script languages.
 */
class JsUiV1IndicatorTest {

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
    fun `Indicator constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var d = ui.Indicator({});
            print(d.kind + " " + d.x + " " + d.y + " " + d.width + " " + d.height);
            print(d.size + " " + d.state + " " + d.label + " " + d.color);
            print(d.offColor + " " + d.labelColor + " " + d.gap + " " + d.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Indicator 0 0 0 0",
            "8 off null null",
            "muted fg 4 true",
        )
    }

    @Test
    fun `Indicator on state draws green LED`() {
        // size=8 -> ry=4, rx=floor(4*20/9 + 0.5)=9. cx=x+rx=19, cy=y+h/2=26.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:10, y:20, width:80, height:12, state:"on" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "fillEllipse(19,26,9,4,0xFF2ECC71)"
    }

    @Test
    fun `Indicator off state uses muted color`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:40, height:12, state:"off" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "fillEllipse(9,6,9,4,0xFF7A8597)"
    }

    @Test
    fun `Indicator warn bad and info map to their tokens`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:40, height:12, state:"warn" }));
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:20, width:40, height:12, state:"bad" }));
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:40, width:40, height:12, state:"info" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "fillEllipse(9,6,9,4,0xFFF1C40F)"
        monitor.calls shouldContain "fillEllipse(9,26,9,4,0xFFE74C3C)"
        monitor.calls shouldContain "fillEllipse(9,46,9,4,0xFF3498DB)"
    }

    @Test
    fun `Indicator color override beats state mapping`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:40, height:12, state:"off", color:0xFFABCDEF|0 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "fillEllipse(9,6,9,4,0xFFABCDEF)"
    }

    @Test
    fun `Indicator size less than 2 clamps to radius 1`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:40, height:12, size:0, state:"on" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // size clamped to 2 -> ry=1, rx=floor(1*20/9 + 0.5)=2. cx=2, cy=6.
        monitor.calls shouldContain "fillEllipse(2,6,2,1,0xFF2ECC71)"
    }

    @Test
    fun `Indicator label writes to cell grid to the right of LED`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:80, height:12, state:"on", label:"PUMP" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // RecordingMonitor defaults: pixel=320x240, text=40x20 -> pxPerCol=8, pxPerRow=12.
        // size=8 -> ry=4, rx=9.
        // With label: cellCol = floor(9/8) = 1, cx = 1*8+4 = 12.
        // textX = cx + rx + gap = 25; textCol = ceil(25/8) = 4.
        monitor.calls shouldContain "fillEllipse(12,6,9,4,0xFF2ECC71)"
        monitor.calls shouldContain "setFg(0xFFFFFFFF)"
        monitor.calls shouldContain "setCursorPos(4,0)"
        monitor.calls shouldContain "write(PUMP)"
    }

    @Test
    fun `Indicator with no label emits no text calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:40, height:12, state:"on" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.startsWith("write(") } shouldBe true
        monitor.calls.none { it.startsWith("setCursorPos(") } shouldBe true
    }

    @Test
    fun `Indicator visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Indicator({ x:0, y:0, width:40, height:12, state:"on",
                                    label:"x", visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Indicator set and get`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var d = ui.Indicator({ state:"off" });
            d.set({ state:"warn", label:"HEAT" });
            print(d.get("state") + " " + d.get("label"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("warn HEAT")
    }
}
