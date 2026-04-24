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
 * Flow container: row-first, wrap when children overflow the inner width.
 *
 * Monitor surface: 320×240 px, 40×20 cells → pxPerCol=8, pxPerRow=12.
 * Card widths snap up to a cell-multiple whose parity matches the
 * content's cell count, and heights snap the same way. Card padding=4 +
 * a 1-char Label → 24×36. With cards present, Flow also snaps inner
 * origin + gap up to cell units so children land on the grid.
 */
class LuaUiV1FlowTest {

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
    fun `Flow packs three short cards into one row when width fits`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local a = ui.Card{ children = { ui.Label{ text="A" } } }   -- 24×36
            local b = ui.Card{ children = { ui.Label{ text="B" } } }   -- 24×36
            local c = ui.Card{ children = { ui.Label{ text="C" } } }   -- 24×36
            local root = ui.Flow{ padding=0, gap=4, children = { a, b, c } }
            ui.mount(mon, root)
            -- With cards, gap=4 snaps to 8. Row sum = 24+8+24+8+24 = 88 << 320.
            print(a.x, a.y, b.x, b.y, c.x, c.y)
            print(root.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // a at x=0, b at x=32 (24+8 snapped gap), c at x=64. All y=0.
        // Row height = 36 (cell-aligned).
        out.lines shouldBe listOf("0\t0\t32\t0\t64\t0", "36")
    }

    @Test
    fun `Flow wraps to a second row when cursor would overflow`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            -- Card("XXXXXXXXXXX") N=11 → cell-aligned to 13*8 = 104 wide, 36 tall.
            -- Snapped gap = 8. Row packs: 104 + 8 + 104 = 216. Adding another
            -- (+8+104 = 328) overflows innerW 320. So 2 per row, 4 total → 2 rows.
            local children = {}
            for i = 1, 4 do
                children[i] = ui.Card{ children = { ui.Label{ text="XXXXXXXXXXX" } } }
            end
            local root = ui.Flow{ padding=0, gap=4, children = children }
            ui.mount(mon, root)
            for i = 1, 4 do
                print(children[i].x, children[i].y)
            end
            print(root.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Row 1: x=0, 112 (104+8). Row 2 at y=44 (36+8 snapped gap): x=0, 112.
        out.lines shouldBe listOf(
            "0\t0",
            "112\t0",
            "0\t44",
            "112\t44",
            "80",  // 36 + 8 + 36 = 80
        )
    }

    @Test
    fun `Flow measure returns single-row estimate`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local f = ui.Flow{ padding=0, gap=2, children = {
                ui.Card{ children = { ui.Label{ text="AB" } } },    -- 32×36
                ui.Card{ children = { ui.Label{ text="CD" } } },    -- 32×36
            }}
            local w, h = f:measure(mon)
            print(w, h)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // 32 + 2 + 32 = 66 wide, 36 tall (single row estimate; measure
        // does not snap gap — only layout does).
        out.lines shouldBe listOf("66\t36")
    }

    @Test
    fun `Flow explicit height is preserved when rows fit`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local a = ui.Card{ children = { ui.Label{ text="A" } } }
            local f = ui.Flow{ height=60, padding=0, gap=0, children = { a } }
            ui.mount(mon, f)
            print(f.height)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Explicit height=60 was honored; layout did not overwrite.
        out.lines shouldBe listOf("60")
    }
}
