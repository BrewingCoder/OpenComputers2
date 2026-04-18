package com.brewingcoder.oc2.platform.monitor

import com.brewingcoder.oc2.platform.Position
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MonitorMergeTest {

    /** Convenience: build an isMonitorAt predicate from an explicit set of positions. */
    private fun world(vararg positions: Position): (Position) -> Boolean {
        val set = positions.toSet()
        return { it in set }
    }

    // ---------- single block & basic shapes ----------

    @Test
    fun `single isolated monitor is a 1x1 group`() {
        val seed = Position(0, 0, 0)
        val g = MonitorMerge.computeGroup(seed, MonitorMerge.Facing.NORTH, world(seed))
        g.width shouldBe 1
        g.height shouldBe 1
        g.members shouldBe setOf(seed)
        g.masterPos shouldBe seed
    }

    @Test
    fun `2x1 horizontal merge facing north`() {
        val a = Position(0, 0, 0)
        val b = Position(1, 0, 0)
        val g = MonitorMerge.computeGroup(a, MonitorMerge.Facing.NORTH, world(a, b))
        g.width shouldBe 2
        g.height shouldBe 1
        g.members shouldBe setOf(a, b)
        // Master = smallest (y, x) → (0, 0) → (0,0,0)
        g.masterPos shouldBe a
    }

    @Test
    fun `1x2 vertical merge`() {
        val bottom = Position(5, 64, 10)
        val top = Position(5, 65, 10)
        val g = MonitorMerge.computeGroup(top, MonitorMerge.Facing.NORTH, world(top, bottom))
        g.width shouldBe 1
        g.height shouldBe 2
        // Master = smallest (y, x) → bottom (lowest y)
        g.masterPos shouldBe bottom
    }

    @Test
    fun `2x2 grid forms a square group`() {
        val tl = Position(0, 1, 0); val tr = Position(1, 1, 0)
        val bl = Position(0, 0, 0); val br = Position(1, 0, 0)
        val g = MonitorMerge.computeGroup(tr, MonitorMerge.Facing.NORTH, world(tl, tr, bl, br))
        g.width shouldBe 2
        g.height shouldBe 2
        g.members shouldBe setOf(tl, tr, bl, br)
        // Master = smallest (y, x) → bl
        g.masterPos shouldBe bl
    }

    @Test
    fun `3x2 rectangle`() {
        val members = setOf(
            Position(0, 0, 0), Position(1, 0, 0), Position(2, 0, 0),
            Position(0, 1, 0), Position(1, 1, 0), Position(2, 1, 0),
        )
        val g = MonitorMerge.computeGroup(Position(1, 1, 0), MonitorMerge.Facing.NORTH, world(*members.toTypedArray()))
        g.width shouldBe 3
        g.height shouldBe 2
        g.members shouldBe members
        g.masterPos shouldBe Position(0, 0, 0)
    }

    // ---------- non-rectangular shapes ----------

    // ---------- non-rectangular shapes — largest-sub-rect rule ----------

    @Test
    fun `L-shape — seed B is orphan because canonical rect is 1x2 vertical`() {
        // L-shape:    [A][B]      max rect = 1x2 vertical (C+A); B is orphan
        //             [C]
        val a = Position(0, 1, 0); val b = Position(1, 1, 0); val c = Position(0, 0, 0)
        val g = MonitorMerge.computeGroup(b, MonitorMerge.Facing.NORTH, world(a, b, c))
        g.width shouldBe 1
        g.height shouldBe 1
        g.members shouldBe setOf(b)
    }

    @Test
    fun `L-shape — seed A picks up the 1x2 vertical canonical rect`() {
        val a = Position(0, 1, 0); val b = Position(1, 1, 0); val c = Position(0, 0, 0)
        val g = MonitorMerge.computeGroup(a, MonitorMerge.Facing.NORTH, world(a, b, c))
        g.width shouldBe 1
        g.height shouldBe 2
        g.members shouldBe setOf(a, c)
        g.masterPos shouldBe c
    }

    @Test
    fun `cross — center seed picks the 1x3 vertical canonical rect (lower top wins tie)`() {
        val center = Position(1, 1, 0)
        val members = setOf(
            center,
            Position(0, 1, 0), Position(2, 1, 0),    // W, E
            Position(1, 0, 0), Position(1, 2, 0),    // S, N
        )
        val g = MonitorMerge.computeGroup(center, MonitorMerge.Facing.NORTH, world(*members.toTypedArray()))
        g.width shouldBe 1
        g.height shouldBe 3
        g.members shouldBe setOf(Position(1, 0, 0), center, Position(1, 2, 0))
    }

    @Test
    fun `cross — east arm is orphan because it's not in the canonical rect`() {
        val east = Position(2, 1, 0)
        val members = setOf(
            Position(1, 1, 0), Position(0, 1, 0), east, Position(1, 0, 0), Position(1, 2, 0),
        )
        val g = MonitorMerge.computeGroup(east, MonitorMerge.Facing.NORTH, world(*members.toTypedArray()))
        g.width shouldBe 1
        g.height shouldBe 1
        g.members shouldBe setOf(east)
    }

    @Test
    fun `2x2 with one missing corner — seed in the canonical 1x2 picks it up`() {
        // [A][B]
        // [C]    <- D missing. Canonical rect = 1x2 vertical (C+A). B is orphan.
        val a = Position(0, 1, 0); val b = Position(1, 1, 0); val c = Position(0, 0, 0)
        val g = MonitorMerge.computeGroup(a, MonitorMerge.Facing.NORTH, world(a, b, c))
        g.width shouldBe 1
        g.height shouldBe 2
        g.members shouldBe setOf(a, c)
    }

    @Test
    fun `3x3 with top-right corner missing — gives a 3x2 bottom group (Scott's case)`() {
        // [A][B][.]
        // [D][E][F]      → canonical max rect = 3x2 bottom (D,E,F,G,H,I)
        // [G][H][I]      → orphans = A, B (each 1x1)
        val cells = listOf(
            Position(0, 2, 0), Position(1, 2, 0),                        // A, B (top row, no C)
            Position(0, 1, 0), Position(1, 1, 0), Position(2, 1, 0),     // D, E, F
            Position(0, 0, 0), Position(1, 0, 0), Position(2, 0, 0),     // G, H, I
        )
        val w = world(*cells.toTypedArray())
        // A seed inside the canonical rect → 3x2 group
        val gFromE = MonitorMerge.computeGroup(Position(1, 1, 0), MonitorMerge.Facing.NORTH, w)
        gFromE.width shouldBe 3
        gFromE.height shouldBe 2
        gFromE.masterPos shouldBe Position(0, 0, 0)  // G is bottom-left
        // A seed outside (top-row orphan) → standalone 1x1
        val gFromA = MonitorMerge.computeGroup(Position(0, 2, 0), MonitorMerge.Facing.NORTH, w)
        gFromA.width shouldBe 1
        gFromA.height shouldBe 1
    }

    // ---------- facing variations ----------

    @Test
    fun `east-facing 2x1 walks the Z axis`() {
        val a = Position(0, 0, 0); val b = Position(0, 0, 1)
        val g = MonitorMerge.computeGroup(a, MonitorMerge.Facing.EAST, world(a, b))
        g.width shouldBe 2
        g.height shouldBe 1
    }

    @Test
    fun `monitors of different facing don't merge — but isMonitorAt encodes that filter`() {
        // North-facing seed; "world" only returns north-facing positions.
        // Adjacent east-facing monitor wouldn't be reported by isMonitorAt.
        val north = Position(0, 0, 0)
        val northAdj = Position(1, 0, 0)
        // East-facing monitor at (1,0,0) is NOT in our north-facing isMonitorAt set:
        val g = MonitorMerge.computeGroup(north, MonitorMerge.Facing.NORTH, world(north))
        g.size shouldBe 1
        // sanity: would have been 2 if both were north-facing
        val g2 = MonitorMerge.computeGroup(north, MonitorMerge.Facing.NORTH, world(north, northAdj))
        g2.size shouldBe 2
    }

    // ---------- master determinism ----------

    @Test
    fun `master is deterministic regardless of which member seeds the search`() {
        val members = setOf(
            Position(0, 0, 0), Position(1, 0, 0),
            Position(0, 1, 0), Position(1, 1, 0),
        )
        val world = world(*members.toTypedArray())
        val masters = members.map { MonitorMerge.computeGroup(it, MonitorMerge.Facing.NORTH, world).masterPos }.toSet()
        // All seeds should compute the same master
        masters.size shouldBe 1
        masters.first() shouldBe Position(0, 0, 0)
    }
}
