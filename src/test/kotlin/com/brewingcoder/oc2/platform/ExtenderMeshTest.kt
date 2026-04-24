package com.brewingcoder.oc2.platform

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExtenderMesh]. Pure platform-layer — no MC on the classpath.
 *
 * Uses the default 64-block radius in most tests since that matches
 * [com.brewingcoder.oc2.block.WiFiExtenderConfig.rangeBlocks] defaults.
 */
class ExtenderMeshTest {

    private val R = 64

    private fun ext(x: Int, y: Int = 0, z: Int = 0, radius: Int = R) =
        ExtenderMesh.Extender(Position(x, y, z), radius)

    @Test
    fun `no extenders means no reachability`() {
        ExtenderMesh.reaches(
            extenders = emptyList(),
            src = Position(0, 0, 0),
            dst = Position(10, 0, 0),
        ) shouldBe false
    }

    @Test
    fun `a single extender covers points within its radius`() {
        val extenders = listOf(ext(0))
        val comps = ExtenderMesh.components(extenders)
        comps shouldHaveSize 1

        // Point inside radius
        ExtenderMesh.anyCovers(extenders, Position(30, 0, 0)) shouldBe true
        // Point exactly at radius (distSqrTo == r*r -> within)
        ExtenderMesh.anyCovers(extenders, Position(R, 0, 0)) shouldBe true
        // Point just beyond radius
        ExtenderMesh.anyCovers(extenders, Position(R + 1, 0, 0)) shouldBe false
    }

    @Test
    fun `two extenders whose disks overlap form one component`() {
        val a = ext(0)
        val b = ext(100)  // 100 < 64+64=128; overlapping disks
        val comps = ExtenderMesh.components(listOf(a, b))
        comps shouldHaveSize 1
        comps[0] shouldHaveSize 2
    }

    @Test
    fun `two extenders whose disks don't overlap form two components`() {
        val a = ext(0)
        val b = ext(200)  // 200 > 64+64=128; no overlap
        val comps = ExtenderMesh.components(listOf(a, b))
        comps shouldHaveSize 2
    }

    @Test
    fun `three extenders in a chain form one component`() {
        // Chain: 0 --- 120 --- 240. Each pair 120 apart, < 128 link threshold.
        val a = ext(0)
        val b = ext(120)
        val c = ext(240)
        val comps = ExtenderMesh.components(listOf(a, b, c))
        comps shouldHaveSize 1
        comps[0] shouldHaveSize 3
    }

    @Test
    fun `two distant computers can chain-reach through three extenders`() {
        // A----[E1]----[E2]----[E3]----B
        // 0     50      170     290     400
        // Pairs: E1-E2 dist 120 OK; E2-E3 dist 120 OK. Chain covers span.
        val extenders = listOf(ext(50), ext(170), ext(290))
        ExtenderMesh.reaches(
            extenders = extenders,
            src = Position(0, 0, 0),
            dst = Position(340, 0, 0),   // within E3's 64-radius
        ) shouldBe true
    }

    @Test
    fun `reaches returns false when src and dst are in different components`() {
        // Two meshes separated by a gap > 2*R so they don't link.
        val mesh1 = listOf(ext(0), ext(100))
        val mesh2 = listOf(ext(500), ext(600))
        val all = mesh1 + mesh2

        ExtenderMesh.components(all) shouldHaveSize 2

        // src near mesh1's E1, dst near mesh2's E1 — not reachable.
        ExtenderMesh.reaches(all, Position(10, 0, 0), Position(510, 0, 0)) shouldBe false

        // But src + dst both near mesh1 — reachable.
        ExtenderMesh.reaches(all, Position(10, 0, 0), Position(90, 0, 0)) shouldBe true
    }

    @Test
    fun `isolated extender with no neighbors still covers its own disk`() {
        val lone = ext(1000)
        ExtenderMesh.reaches(
            extenders = listOf(lone),
            src = Position(990, 0, 0),
            dst = Position(1010, 0, 0),
        ) shouldBe true
    }

    @Test
    fun `reaches with src or dst outside all disks returns false`() {
        val extenders = listOf(ext(0), ext(100))
        // src covered, dst outside any disk
        ExtenderMesh.reaches(extenders, Position(5, 0, 0), Position(9999, 0, 0)) shouldBe false
        // dst covered, src outside
        ExtenderMesh.reaches(extenders, Position(9999, 0, 0), Position(5, 0, 0)) shouldBe false
    }

    @Test
    fun `components handles empty list`() {
        ExtenderMesh.components(emptyList()).shouldBeEmpty()
    }

    @Test
    fun `linked honors sum of radii not doubled radius`() {
        // Asymmetric radii: a radius 30, b radius 70. Link threshold = 100.
        val a = ext(0, radius = 30)
        val b = ext(99, radius = 70)
        val c = ext(101, radius = 70)
        ExtenderMesh.linked(a, b) shouldBe true
        ExtenderMesh.linked(a, c) shouldBe false
    }

    @Test
    fun `pointCovered only returns true when at least one member covers`() {
        val comp = listOf(ext(0), ext(100))
        ExtenderMesh.pointCovered(comp, Position(30, 0, 0)) shouldBe true
        ExtenderMesh.pointCovered(comp, Position(130, 0, 0)) shouldBe true
        ExtenderMesh.pointCovered(comp, Position(300, 0, 0)) shouldBe false
    }

    @Test
    fun `reaches is symmetric on src dst`() {
        val extenders = listOf(ext(0), ext(120), ext(240))
        val p1 = Position(10, 0, 0)
        val p2 = Position(290, 0, 0)
        ExtenderMesh.reaches(extenders, p1, p2) shouldBe
            ExtenderMesh.reaches(extenders, p2, p1)
    }

    @Test
    fun `3d extender mesh links on vertical distance too`() {
        // Two extenders stacked vertically, 100 blocks apart in Y.
        val lower = ext(0, y = 0)
        val upper = ext(0, y = 100)
        ExtenderMesh.linked(lower, upper) shouldBe true

        // ...but 200 apart they don't link (> 2R = 128).
        val top = ext(0, y = 200)
        ExtenderMesh.linked(lower, top) shouldBe false
    }
}
