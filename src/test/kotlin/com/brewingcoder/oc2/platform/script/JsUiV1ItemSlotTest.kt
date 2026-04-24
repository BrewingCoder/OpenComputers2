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
 * Unit tests for `ui_v1`'s ItemSlot widget (JS). Mirror of [LuaUiV1ItemSlotTest].
 */
class JsUiV1ItemSlotTest {

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
    fun `ItemSlot constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.ItemSlot({});
            print(s.kind + " " + s.x + " " + s.y + " " + s.width + " " + s.height);
            print(s.item + " " + s.fluid + " " + s.chemical);
            print(s.count + " " + s.caption + " " + s.size);
            print(s.bg + " " + s.border + " " + s.countColor + " " + s.captionColor);
            print(s.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "ItemSlot 0 0 0 0",
            "null null null",
            "null null 72",
            "bgCard edge hi muted",
            "true",
        )
    }

    @Test
    fun `ItemSlot ctor applies props`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.ItemSlot({
                x:3, y:5, width:40, height:50,
                item:"minecraft:iron_ingot", count:42, caption:"IRON",
                size:36, bg:"bgTerm", border:null,
                countColor:"good", captionColor:"warn",
            });
            print(s.x + " " + s.y + " " + s.width + " " + s.height);
            print(s.item + " " + s.count + " " + s.caption);
            print(s.size + " " + s.bg + " " + s.border);
            print(s.countColor + " " + s.captionColor);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "3 5 40 50",
            "minecraft:iron_ingot 42 IRON",
            "36 bgTerm null",
            "good warn",
        )
    }

    @Test
    fun `ItemSlot with item renders clearIcons then drawItem with aspect-corrected rect`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32,
                                   item:"minecraft:redstone" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly listOf(
            "drawRect(0,0,32,32,0xFF121827)",
            "clearIcons()",
            "drawItem(2,9,28,13,minecraft:redstone)",
            "drawRectOutline(0,0,32,32,0xFF2E3A4E,t=1)",
        )
    }

    @Test
    fun `ItemSlot without resource emits only slot chrome`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("clearIcons(") } shouldBe false
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1
        monitor.calls.count { it.startsWith("drawRectOutline(") } shouldBe 1
    }

    @Test
    fun `ItemSlot with fluid routes through drawFluid`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32,
                                   fluid:"minecraft:water" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("clearIcons(") } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.any { it.startsWith("drawFluid(") && it.contains("minecraft:water") } shouldBe true
    }

    @Test
    fun `ItemSlot fluid takes the same inset rect as item`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32,
                                   fluid:"minecraft:lava", bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it == "drawFluid(2,9,28,13,minecraft:lava)" } shouldBe true
    }

    @Test
    fun `ItemSlot with chemical routes through drawChemical`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32,
                                   chemical:"mekanism:hydrogen",
                                   bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it == "drawChemical(2,9,28,13,mekanism:hydrogen)" } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.any { it.startsWith("drawFluid(") } shouldBe false
    }

    @Test
    fun `Icon shape=chemical routes through drawChemical`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Icon({ x:0, y:0, width:40, height:40,
                               shape:"chemical", chemical:"mekanism:oxygen" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("drawChemical(") && it.contains("mekanism:oxygen") } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.any { it.startsWith("drawFluid(") } shouldBe false
    }

    @Test
    fun `Icon shape=fluid routes through drawFluid`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Icon({ x:0, y:0, width:40, height:40,
                               shape:"fluid", fluid:"minecraft:water" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("drawFluid(") && it.contains("minecraft:water") } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
    }

    @Test
    fun `ItemSlot count overlay draws centered below icon`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32,
                                   item:"minecraft:redstone",
                                   count:5, bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // Cell-snap cols=4, centered "5" in 4 cols → startCol=1, countRow=1.
        val setCursor = monitor.calls.filter { it.startsWith("setCursorPos(") }
        setCursor shouldContainExactly listOf("setCursorPos(1,1)")
        monitor.calls.any { it == "write(5)" } shouldBe true
    }

    @Test
    fun `ItemSlot count formatter abbreviates large numbers`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            // width=64 → 8 cells wide, so no clipping for these count strings.
            ui.mount(mon, ui.ItemSlot({ x:0,   y:0, width:64, height:64,
                                        item:"minecraft:iron_ingot", count:5,
                                        bg:null, border:null }));
            ui.mount(mon, ui.ItemSlot({ x:72,  y:0, width:64, height:64,
                                        item:"minecraft:iron_ingot", count:12500,
                                        bg:null, border:null }));
            ui.mount(mon, ui.ItemSlot({ x:144, y:0, width:64, height:64,
                                        item:"minecraft:iron_ingot", count:2500000,
                                        bg:null, border:null }));
            ui.mount(mon, ui.ItemSlot({ x:216, y:0, width:64, height:64,
                                        item:"minecraft:iron_ingot", count:"CUSTOM",
                                        bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val writes = monitor.calls.filter { it.startsWith("write(") }
        writes.any { it == "write(5)" } shouldBe true
        writes.any { it == "write(12.5k)" } shouldBe true
        writes.any { it == "write(2.5M)" } shouldBe true
        writes.any { it == "write(CUSTOM)" } shouldBe true
    }

    @Test
    fun `ItemSlot count clips to slot width so it can't spill into neighbors`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:16, height:32,
                                   item:"minecraft:iron_ingot",
                                   count:12500, bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val writes = monitor.calls.filter { it.startsWith("write(") }
        writes.any { it == "write(12)" } shouldBe true
        writes.any { it.contains("12.5k") } shouldBe false
    }

    @Test
    fun `ItemSlot caption clips to slot width so it can't spill into neighbors`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:16, height:44,
                                   item:"minecraft:iron_ingot",
                                   caption:"VAULT", bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val writes = monitor.calls.filter { it.startsWith("write(") }
        writes.any { it == "write(VA)" } shouldBe true
        writes.any { it.contains("VAULT") } shouldBe false
    }

    @Test
    fun `ItemSlot caption draws one cell row below icon area`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:44,
                                   item:"minecraft:iron_ingot",
                                   caption:"IRON",
                                   bg:null, border:null }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val setCursor = monitor.calls.filter { it.startsWith("setCursorPos(") }
        setCursor.any { it == "setCursorPos(0,2)" } shouldBe true
        monitor.calls.any { it == "write(IRON)" } shouldBe true
    }

    @Test
    fun `ItemSlot measure returns size+captionRow when caption set`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var a = ui.ItemSlot({ size:32 });
            var b = ui.ItemSlot({ size:32, caption:"X" });
            var am = a.measure(mon);
            var bm = b.measure(mon);
            print(am[0] + "x" + am[1] + " " + bm[0] + "x" + bm[1]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("32x32 32x44")
    }

    @Test
    fun `ItemSlot clearIcons fires exactly once per render across multiple slots`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.VBox({ padding:0, gap:0, children:[
                         ui.ItemSlot({ width:32, height:32, item:"minecraft:redstone",
                                       bg:null, border:null }),
                         ui.ItemSlot({ width:32, height:32, item:"minecraft:coal",
                                       bg:null, border:null }),
                         ui.ItemSlot({ width:32, height:32, item:"minecraft:stone",
                                       bg:null, border:null }),
                     ]}));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it == "clearIcons()" } shouldBe 1
        monitor.calls.count { it.startsWith("drawItem(") } shouldBe 3
    }

    @Test
    fun `ItemSlot visible=false emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:32,
                                   item:"minecraft:redstone", visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `ItemSlot zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:0, height:32,
                                   item:"minecraft:redstone" }));
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot({ x:0, y:0, width:32, height:0,
                                   item:"minecraft:redstone" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }
}
