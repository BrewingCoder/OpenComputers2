package com.brewingcoder.oc2.platform.monitor

import com.brewingcoder.oc2.platform.Position

/**
 * Pure logic for the rectangular merge of monitor blocks. Lives in `platform/`
 * (Rule D) so the algorithm can be unit-tested without MC on the classpath.
 *
 * Inputs:
 *   - the seed monitor's position and facing direction
 *   - a callback that, given a position, returns whether a same-facing monitor
 *     occupies it (the BE wires this to the level lookup)
 *
 * Output:
 *   - a [MonitorGroup] describing the rectangle the seed is part of, OR
 *   - a degenerate 1×1 group if the seed is isolated or the surrounding shape
 *     isn't rectangular
 *
 * Conventions:
 *   - Wall-mount only (facings N/S/E/W). The "screen plane" is the plane
 *     perpendicular to the facing direction, parameterized by two axes:
 *     - `horiz` axis = perpendicular to facing in the horizontal plane (X if facing N/S, Z if facing E/W)
 *     - `vert` axis = world Y
 *   - The group's master is the block with the smallest (vert, horiz) coords —
 *     deterministic so all peers agree on who's master.
 */
object MonitorMerge {

    /** Computed merge result. */
    data class MonitorGroup(
        val masterPos: Position,
        val facing: Facing,
        /** All positions in the group, including the master. */
        val members: Set<Position>,
        /** Group dimensions in monitor-blocks. */
        val width: Int,
        val height: Int,
    ) {
        val isMaster: (Position) -> Boolean = { it == masterPos }
        val size: Int get() = members.size
    }

    /** Wall-mount facings supported by v0. */
    enum class Facing { NORTH, SOUTH, EAST, WEST }

    /**
     * Compute the rectangular group that the [seed] belongs to. The algorithm
     * finds the largest filled rectangle within the seed's connected component
     * — players intuit this as "carve out the biggest panel possible from
     * what's there." Cells in the component but outside that rectangle are
     * orphans (each forms its own 1×1 group when re-evaluated from their seed).
     *
     * This replaced the older "all-or-nothing" rule (any non-rectangular shape
     * → everyone goes 1×1), which surprised users by collapsing the entire
     * group when one block broke.
     *
     * Algorithm:
     *   1. Flood-fill the connected component
     *   2. Find the canonical max-area rectangle within it (deterministic; all
     *      seeds in the same component agree on the rectangle)
     *   3. If seed ∈ rectangle → group = rectangle
     *   4. Else → group = seed-only 1×1 (orphan)
     *
     * Step 2's tiebreaking (when multiple max-area rectangles exist):
     *   - Lower (vert, horiz) corner wins (top-left in screen-plane terms)
     *   - Then wider rectangle wins (so a 3×2 beats a 2×3 of equal area)
     */
    fun computeGroup(
        seed: Position,
        facing: Facing,
        isMonitorAt: (Position) -> Boolean,
    ): MonitorGroup {
        // 1. Flood-fill connected same-facing monitors
        val connected = mutableSetOf<Position>()
        val queue = ArrayDeque<Position>()
        queue.add(seed)
        connected.add(seed)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (n in inPlaneNeighbors(cur, facing)) {
                if (n !in connected && isMonitorAt(n)) {
                    connected.add(n)
                    queue.add(n)
                }
            }
        }

        // 2. Canonical max-area rectangle within the component
        val planeCoords = connected.map { worldToPlane(it, facing) }.toSet()
        val rect = findMaxRectangle(planeCoords)
        val seedPlane = worldToPlane(seed, facing)

        // 3 & 4: seed in rect → group is the rect; otherwise standalone 1×1
        return if (seedPlane in rect.cells) {
            val masterPos = planeToWorld(rect.topLeft, seed, facing)
            val members = rect.cells.map { planeToWorld(it, seed, facing) }.toSet()
            MonitorGroup(masterPos, facing, members, rect.width, rect.height)
        } else {
            MonitorGroup(seed, facing, setOf(seed), 1, 1)
        }
    }

    /** Result of a max-rect search — top-left corner, dimensions, and cells in plane coords. */
    private data class Rect(val topLeft: Pair<Int, Int>, val width: Int, val height: Int) {
        val area: Int get() = width * height
        val cells: Set<Pair<Int, Int>> = buildSet {
            for (v in topLeft.second until topLeft.second + height) {
                for (h in topLeft.first until topLeft.first + width) {
                    add(h to v)
                }
            }
        }
    }

    /**
     * Brute-force max-area rectangle search. O((maxV-minV)² × (maxH-minH)²) which is
     * fine for monitor groups (typically 1×1 to 5×5; 9×9 = 6561 iterations worst case).
     * Caps loop bounds to the bbox of [filled].
     *
     * Tiebreak: max area, then smallest (top, left), then largest width.
     */
    private fun findMaxRectangle(filled: Set<Pair<Int, Int>>): Rect {
        require(filled.isNotEmpty())
        val minH = filled.minOf { it.first }
        val maxH = filled.maxOf { it.first }
        val minV = filled.minOf { it.second }
        val maxV = filled.maxOf { it.second }

        var best = Rect(minH to minV, 1, 1)  // any single cell guaranteed to be valid
        // Find a real cell to seed `best` (filled is non-empty so this always succeeds)
        val seedCell = filled.first()
        best = Rect(seedCell, 1, 1)

        for (top in minV..maxV) {
            for (bottom in top..maxV) {
                for (left in minH..maxH) {
                    for (right in left..maxH) {
                        // All cells in [top..bottom] × [left..right] must be filled
                        var ok = true
                        outer@ for (v in top..bottom) {
                            for (h in left..right) {
                                if ((h to v) !in filled) { ok = false; break@outer }
                            }
                        }
                        if (!ok) continue
                        val w = right - left + 1
                        val h = bottom - top + 1
                        val area = w * h
                        // Deterministic tiebreak: bigger area > smaller (top,left) > wider
                        val better = when {
                            area > best.area -> true
                            area < best.area -> false
                            top < best.topLeft.second -> true
                            top > best.topLeft.second -> false
                            left < best.topLeft.first -> true
                            left > best.topLeft.first -> false
                            w > best.width -> true
                            else -> false
                        }
                        if (better) best = Rect(left to top, w, h)
                    }
                }
            }
        }
        return best
    }

    /** Four in-plane neighbors of [pos] perpendicular to [facing]. */
    fun inPlaneNeighbors(pos: Position, facing: Facing): List<Position> = when (facing) {
        Facing.NORTH, Facing.SOUTH -> listOf(
            Position(pos.x - 1, pos.y, pos.z),
            Position(pos.x + 1, pos.y, pos.z),
            Position(pos.x, pos.y - 1, pos.z),
            Position(pos.x, pos.y + 1, pos.z),
        )
        Facing.EAST, Facing.WEST -> listOf(
            Position(pos.x, pos.y, pos.z - 1),
            Position(pos.x, pos.y, pos.z + 1),
            Position(pos.x, pos.y - 1, pos.z),
            Position(pos.x, pos.y + 1, pos.z),
        )
    }

    /** Project a world position to (horizCoord, vertCoord) in the screen plane. */
    private fun worldToPlane(pos: Position, facing: Facing): Pair<Int, Int> = when (facing) {
        Facing.NORTH, Facing.SOUTH -> pos.x to pos.y
        Facing.EAST, Facing.WEST -> pos.z to pos.y
    }

    /** Inverse of [worldToPlane], holding the constant-axis coord from a reference position. */
    private fun planeToWorld(plane: Pair<Int, Int>, reference: Position, facing: Facing): Position =
        when (facing) {
            Facing.NORTH, Facing.SOUTH -> Position(plane.first, plane.second, reference.z)
            Facing.EAST, Facing.WEST -> Position(reference.x, plane.second, plane.first)
        }
}
