package com.brewingcoder.oc2.platform

/**
 * Pure-Kotlin reachability math for WiFi Extenders. Given a set of active
 * extenders on a channel and a per-extender broadcast radius, compute:
 *
 *   - which extenders chain together (connected components by mutual range)
 *   - whether an arbitrary query point is covered by any extender in a
 *     component that also covers a second point
 *
 * This lives under `platform/` (Rule D — no `net.minecraft.*` imports) so it's
 * unit-testable without a running server. Production callers wrap
 * [ChannelRegistry.listOnChannel] into a list of [Position] + range pairs and
 * hand it to [coverageFor].
 *
 * Semantic: the mesh EXPANDS effective reach. Two computers on the same
 * channel that are farther apart than a single extender can reach still talk
 * if a chain of extenders links their respective 64-radius disks.
 */
object ExtenderMesh {

    /** One active extender's broadcast footprint: center + Euclidean radius in blocks. */
    data class Extender(val position: Position, val radius: Int)

    /**
     * True iff [a] and [b] can be linked directly (their disks overlap, or one
     * is within the other's radius — same thing when radii are equal).
     * Uses half-distance overlap semantics: a chain forms when centers are
     * within (r_a + r_b) blocks.
     */
    fun linked(a: Extender, b: Extender): Boolean {
        val r = (a.radius + b.radius).toLong()
        return a.position.distanceSqTo(b.position) <= r * r
    }

    /**
     * Partition [extenders] into mesh components by [linked] adjacency.
     * Returns a list of components; each component is the set of extenders
     * that transitively reach each other.
     *
     * Union-find over the input list; O(n² · α(n)) worst case. The set is
     * expected to be small (dozens, not thousands), so that's fine.
     */
    fun components(extenders: List<Extender>): List<List<Extender>> {
        if (extenders.isEmpty()) return emptyList()
        val parent = IntArray(extenders.size) { it }

        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(i: Int, j: Int) {
            val ri = find(i); val rj = find(j)
            if (ri != rj) parent[ri] = rj
        }

        for (i in extenders.indices) {
            for (j in (i + 1) until extenders.size) {
                if (linked(extenders[i], extenders[j])) union(i, j)
            }
        }

        val groups = mutableMapOf<Int, MutableList<Extender>>()
        for (i in extenders.indices) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(extenders[i])
        }
        return groups.values.map { it.toList() }
    }

    /**
     * True iff [point] is within the broadcast footprint of any extender in
     * the supplied component.
     */
    fun pointCovered(component: List<Extender>, point: Position): Boolean =
        component.any { it.position.isWithin(point, it.radius) }

    /**
     * True iff [src] and [dst] share a mesh component's coverage — i.e. at
     * least one extender in [extenders] covers [src] AND at least one (possibly
     * the same, possibly a chain neighbor) in the same connected component
     * covers [dst].
     *
     * This is the primary API: "can a message from src reach dst via the
     * extender mesh, ignoring whether they're already on a shared channel?"
     */
    fun reaches(extenders: List<Extender>, src: Position, dst: Position): Boolean {
        if (extenders.isEmpty()) return false
        for (comp in components(extenders)) {
            if (pointCovered(comp, src) && pointCovered(comp, dst)) return true
        }
        return false
    }

    /**
     * True iff [point] is covered by ANY extender in [extenders], regardless of
     * which component. Useful for "is this location on-mesh at all."
     */
    fun anyCovers(extenders: List<Extender>, point: Position): Boolean =
        extenders.any { it.position.isWithin(point, it.radius) }
}
