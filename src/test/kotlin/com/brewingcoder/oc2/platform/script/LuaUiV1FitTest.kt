package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.ResourceMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Hug-content (measure-to-fit) tests for the Lua ui_v1 layout pass.
 *
 * Monitor surface: 320×240 px, 40×20 cells → pxPerCol=8, pxPerRow=12.
 *
 * Expected sizes for a Label whose text is N chars:
 *   measure() → (N * 8, 12)
 *
 * Card rounds its own (width, height) up to a cell-multiple whose parity
 * matches the content's cell count, so the contained Label lands
 * symmetrically on the cell grid. For a default padding=4 Card wrapping an
 * N-char Label:
 *   width  = nextOddAtLeast(ceil((N*8 + 8) / 8)) * 8  (matching odd parity of N-cell content)
 *          = (N + 2) * 8  for N odd, (N + 2) * 8 for N even adjusted for parity
 *   height = 36  (1-cell content → 3 cells = 36px)
 * Concretely: Card("Hi") = 32×36, Card("A") = 24×36, Card("Bee") = 40×36,
 * Card("Rest") = 48×36.
 */
class LuaUiV1FitTest {

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
    fun `Label_measure returns text-pixel width and one row height`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local lbl = ui.Label{ text="Hello" }    -- 5 chars × 8 = 40 px wide
            local w, h = lbl:measure(mon)
            print(w, h)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("40\t12")
    }

    @Test
    fun `Card_measure wraps child plus padding`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local card = ui.Card{ children = { ui.Label{ text="Hi" } } }
            local w, h = card:measure(mon)
            print(w, h)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Card("Hi"): content 16×12 (2 cells × 1 cell). Padding=4 pushes to
        // 24×20; that snaps up to 32×36 (4 cols, 3 rows — parity match with
        // 2-cell content = even, 1-cell = odd respectively).
        out.lines shouldBe listOf("32\t36")
    }

    @Test
    fun `VBox hug-sizes a Card child to its label height`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local card = ui.Card{ children = { ui.Label{ text="Hi" } } }
            local root = ui.VBox{ padding=0, gap=0, children = { card } }
            ui.mount(mon, root)
            print(card.x, card.y, card.width, card.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // VBox auto-fills 320×240. pad=0. Card with no explicit width/height
        // hugs both axes and snaps to the cell grid → 32×36.
        out.lines shouldBe listOf("0\t0\t32\t36")
    }

    @Test
    fun `VBox stacks two hug-sized Cards with gap`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local a = ui.Card{ children = { ui.Label{ text="A" } } }
            local b = ui.Card{ children = { ui.Label{ text="Bee" } } }
            local root = ui.VBox{ padding=0, gap=4, children = { a, b } }
            ui.mount(mon, root)
            print(a.y, a.height, b.y, b.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Card("A") h=36 at y=0. gap=4. Card("Bee") h=36 at y=40 (36+4).
        out.lines shouldBe listOf("0\t36\t40\t36")
    }

    @Test
    fun `explicit height still wins over hug-content`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local card = ui.Card{ height=60, children = { ui.Label{ text="Hi" } } }
            local root = ui.VBox{ padding=0, gap=0, children = { card } }
            ui.mount(mon, root)
            print(card.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("60")
    }

    @Test
    fun `flex still wins over hug-content`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            -- Flex card takes the 240 px less what fit card uses; fit card = 36.
            local fit = ui.Card{ children = { ui.Label{ text="Hi" } } }
            local fill = ui.Card{ flex=1, children = { ui.Label{ text="Rest" } } }
            local root = ui.VBox{ padding=0, gap=0, children = { fit, fill } }
            ui.mount(mon, root)
            print(fit.height, fill.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // fit=36 (cell-aligned), remainder=240-36=204.
        out.lines shouldBe listOf("36\t204")
    }
}
