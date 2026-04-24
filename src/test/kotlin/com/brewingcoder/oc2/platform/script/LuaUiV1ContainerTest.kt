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
 * Unit tests for `ui_v1`'s layout containers (VBox, HBox, Spacer, Card) in Lua.
 * Covers the Flutter-style measure→layout pass, flex distribution, padding/gap,
 * Card chrome, and mount auto-sizing. Monitor surface is the default 320×240 px.
 */
class LuaUiV1ContainerTest {

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
    fun `VBox HBox Spacer Card constructors exist`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            print(type(ui.VBox), type(ui.HBox), type(ui.Spacer), type(ui.Card))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("function\tfunction\tfunction\tfunction")
    }

    @Test
    fun `VBox stacks fixed-height children top-to-bottom`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ text="a", height=20 }
            local b = ui.Label{ text="b", height=30 }
            local c = ui.Label{ text="c", height=40 }
            local box = ui.VBox{ x=0, y=0, width=200, height=200, children={a, b, c} }
            box:layout()
            print(a.x, a.y, a.width, a.height)
            print(b.x, b.y, b.width, b.height)
            print(c.x, c.y, c.width, c.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0\t0\t200\t20",
            "0\t20\t200\t30",
            "0\t50\t200\t40",
        )
    }

    @Test
    fun `HBox stacks fixed-width children left-to-right`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ text="a", width=40 }
            local b = ui.Label{ text="b", width=60 }
            local box = ui.HBox{ x=10, y=20, width=200, height=48, children={a, b} }
            box:layout()
            print(a.x, a.y, a.width, a.height)
            print(b.x, b.y, b.width, b.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "10\t20\t40\t48",
            "50\t20\t60\t48",
        )
    }

    @Test
    fun `Spacer defaults to flex=1 and eats remainder in VBox`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local top = ui.Label{ text="top", height=10 }
            local sp  = ui.Spacer{}
            local bot = ui.Label{ text="bot", height=20 }
            local box = ui.VBox{ x=0, y=0, width=100, height=100, children={top, sp, bot} }
            box:layout()
            print(sp.flex)
            print(top.y, top.height)
            print(sp.y,  sp.height)
            print(bot.y, bot.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "1",
            "0\t10",
            "10\t70",
            "80\t20",
        )
    }

    @Test
    fun `multiple flex children share the remainder proportionally`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ flex=1 }
            local b = ui.Label{ flex=3 }
            local box = ui.HBox{ x=0, y=0, width=120, height=40, children={a, b} }
            box:layout()
            print(a.x, a.width)
            print(b.x, b.width)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // 120px split 1:3 → a=30, b=90
        out.lines shouldContainExactly listOf(
            "0\t30",
            "30\t90",
        )
    }

    @Test
    fun `fixed + flex children subtract fixed before distributing remainder`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local fixed = ui.Label{ height=40 }
            local flex  = ui.Label{ flex=1 }
            local box = ui.VBox{ x=0, y=0, width=50, height=100, children={fixed, flex} }
            box:layout()
            print(fixed.y, fixed.height)
            print(flex.y,  flex.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // fixed 40, remainder 60 → flex takes 60
        out.lines shouldContainExactly listOf(
            "0\t40",
            "40\t60",
        )
    }

    @Test
    fun `gap prop inserts space between visible children`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ height=10 }
            local b = ui.Label{ height=10 }
            local c = ui.Label{ height=10 }
            local box = ui.VBox{ x=0, y=0, width=50, height=100, gap=5, children={a,b,c} }
            box:layout()
            print(a.y, b.y, c.y)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // 0, 10+5=15, 15+10+5=30
        out.lines shouldContainExactly listOf("0\t15\t30")
    }

    @Test
    fun `padding shrinks the inner rect for children`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local child = ui.Label{ height=10 }
            local box = ui.VBox{ x=0, y=0, width=100, height=50, padding=8, children={child} }
            box:layout()
            print(child.x, child.y, child.width, child.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // inner = (8,8,84,34); fixed-height 10 stays 10, cross stretches to innerW 84.
        out.lines shouldContainExactly listOf("8\t8\t84\t10")
    }

    @Test
    fun `invisible children are skipped in layout`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ height=10 }
            local ghost = ui.Label{ height=999, visible=false }
            local c = ui.Label{ height=20 }
            local box = ui.VBox{ x=0, y=0, width=50, height=100, children={a, ghost, c} }
            box:layout()
            print(a.y, c.y)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // ghost doesn't eat space and doesn't push c down.
        out.lines shouldContainExactly listOf("0\t10")
    }

    @Test
    fun `VBox emits no chrome calls by default`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.VBox{ x=0, y=0, width=100, height=50, children={} })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Card draws bg rect then border before children`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Card{ x=0, y=0, width=80, height=40, children={} })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly listOf(
            "drawRect(0,0,80,40,0xFF121827)",
            "drawRectOutline(0,0,80,40,0xFF2E3A4E,t=1)",
        )
    }

    @Test
    fun `Card defaults padding to 4`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local child = ui.Label{ height=10 }
            local card = ui.Card{ x=0, y=0, width=80, height=40, children={child} }
            card:layout()
            print(card.padding)
            print(child.x, child.y, child.width)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // inner = (4,4,72,32); child takes (4,4,72,10)
        out.lines shouldContainExactly listOf("4", "4\t4\t72")
    }

    @Test
    fun `Card padding=0 is preserved when explicitly passed`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local c = ui.Card{ padding=0 }
            print(c.padding)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("0")
    }

    @Test
    fun `Card border=false disables the outline`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Card{ x=0, y=0, width=80, height=40, border=0, children={} })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // bg draws, but border=0 resolves to 0 and is skipped.
        monitor.calls.none { it.startsWith("drawRectOutline") } shouldBe true
    }

    @Test
    fun `mount auto-sizes a container root to the monitor pixel bounds`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local box = ui.VBox{ children={} }  -- no width/height
            ui.mount(mon, box)
            print(box.x, box.y, box.width, box.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Default RecordingMonitor pixel size = 320x240
        out.lines shouldContainExactly listOf("0\t0\t320\t240")
    }

    @Test
    fun `nested HBox inside VBox lays out children correctly`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ width=30 }
            local b = ui.Label{ width=50 }
            local row = ui.HBox{ height=20, children={a, b} }
            local top = ui.Label{ height=10 }
            local box = ui.VBox{ x=0, y=0, width=100, height=100, children={top, row} }
            box:layout()
            print(top.x, top.y, top.width, top.height)
            print(row.x, row.y, row.width, row.height)
            print(a.x, a.y, a.width, a.height)
            print(b.x, b.y, b.width, b.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0\t0\t100\t10",
            "0\t10\t100\t20",
            "0\t10\t30\t20",
            "30\t10\t50\t20",
        )
    }

    @Test
    fun `Card with explicit bg token overrides default palette`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Card{ x=0, y=0, width=40, height=20, bg="good", border=0, children={} })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,40,20,0xFF2ECC71)"
    }

    @Test
    fun `VBox with explicit bg draws chrome even without Card`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.VBox{ x=0, y=0, width=40, height=20, bg="bgCard", children={} })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,40,20,0xFF121827)"
    }
}
