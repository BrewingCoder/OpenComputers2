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
 * Unit tests for `ui_v1`'s Stack overlay container (Lua). Mirror of [JsUiV1StackTest].
 * Stack semantics: every child receives the Stack's full inner rect; children are
 * drawn back-to-front (first = behind, last = on top); hit-test walks front-to-back.
 */
class LuaUiV1StackTest {

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
    fun `Stack constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local s = ui.Stack{}
            print(s.kind, s.x, s.y, s.width, s.height, s.padding)
            print(#s.children, tostring(s.visible))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Stack\t0\t0\t0\t0\t0",
            "0\ttrue",
        )
    }

    @Test
    fun `Stack gives every child the same inner rect`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ text="A" }
            local b = ui.Label{ text="B" }
            local c = ui.Label{ text="C" }
            local s = ui.Stack{ x=10, y=20, width=100, height=60, children={a,b,c} }
            s:layout()
            print(a.x, a.y, a.width, a.height)
            print(b.x, b.y, b.width, b.height)
            print(c.x, c.y, c.width, c.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "10\t20\t100\t60",
            "10\t20\t100\t60",
            "10\t20\t100\t60",
        )
    }

    @Test
    fun `Stack padding shrinks the inner rect for all children`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ text="A" }
            local b = ui.Label{ text="B" }
            local s = ui.Stack{ x=0, y=0, width=100, height=60, padding=8, children={a,b} }
            s:layout()
            print(a.x, a.y, a.width, a.height)
            print(b.x, b.y, b.width, b.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "8\t8\t84\t44",
            "8\t8\t84\t44",
        )
    }

    @Test
    fun `Stack draws children in declaration order so last child is on top`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            -- back = blue rect, mid = green rect, top = red rect; all three
            -- should emit drawRect in order.
            ui.mount(peripheral.find("monitor"),
                ui.Stack{ x=0, y=0, width=40, height=40, children={
                    ui.Icon{ shape="rect", color=0xFF0000FF },
                    ui.Icon{ shape="rect", color=0xFF00FF00 },
                    ui.Icon{ shape="rect", color=0xFFFF0000 },
                }})
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        val rects = monitor.calls.filter { it.startsWith("drawRect(") }
        rects.size shouldBe 3
        // Declaration order preserved = back-to-front draw order.
        val colors = rects.map { it.substringAfterLast(",").removeSuffix(")").trim() }
        colors shouldContainExactly listOf("0xFF0000FF", "0xFF00FF00", "0xFFFF0000")
    }

    @Test
    fun `Stack measure returns max of children plus padding`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            -- Icon has no measure(), so Stack falls back to explicit width/height.
            local a = ui.Icon{ width=30, height=20 }
            local b = ui.Icon{ width=50, height=10 }
            local s = ui.Stack{ padding=4, children={a,b} }
            local w, h = s:measure()
            print(w, h)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // max(30,50)+2*4 = 58; max(20,10)+2*4 = 28
        out.lines shouldContainExactly listOf("58\t28")
    }

    @Test
    fun `Stack visible=false skips draw entirely`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                ui.Stack{ x=0, y=0, width=40, height=40, visible=false, children={
                    ui.Icon{ shape="rect", color="info" },
                }})
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `Stack bg and border emit chrome before children`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                ui.Stack{ x=0, y=0, width=40, height=40, bg="bgCard", border="edge",
                          children={ ui.Icon{ shape="rect", color="info" } }})
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // 1 drawRect for the bg, 1 for the Icon body = 2 total
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 2
        monitor.calls.any { it.startsWith("drawRectOutline(") } shouldBe true
    }

    @Test
    fun `Stack hit-test picks the topmost child (last in list)`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local clicks = {}
            local back = ui.Button{ label="BACK",
                onClick=function() table.insert(clicks, "back") end }
            local top  = ui.Button{ label="TOP",
                onClick=function() table.insert(clicks, "top") end }
            ui.mount(peripheral.find("monitor"),
                ui.Stack{ x=0, y=0, width=80, height=40, children={back, top} })
            -- Both buttons fill the Stack inner rect -- only 'top' (last
            -- child = front-most in hit-test walk) should fire.
            ui.tick({"monitor_touch", 2, 1, 20, 20, "tester"})
            for _, c in ipairs(clicks) do print(c) end
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("top")
    }

    @Test
    fun `Stack hides invisible children from draw`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                ui.Stack{ x=0, y=0, width=40, height=40, children={
                    ui.Icon{ shape="rect", color="info" },
                    ui.Icon{ shape="rect", color="good", visible=false },
                }})
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1
    }

    @Test
    fun `Stack ignores flex on children (all get full rect)`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local a = ui.Label{ text="A", flex=1 }
            local b = ui.Label{ text="B", flex=3 }
            local s = ui.Stack{ x=0, y=0, width=80, height=40, children={a,b} }
            s:layout()
            print(a.x, a.y, a.width, a.height)
            print(b.x, b.y, b.width, b.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Both children fill the full inner rect -- no flex distribution.
        out.lines shouldContainExactly listOf(
            "0\t0\t80\t40",
            "0\t0\t80\t40",
        )
    }
}
