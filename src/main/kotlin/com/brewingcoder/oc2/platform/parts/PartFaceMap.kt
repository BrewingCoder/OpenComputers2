package com.brewingcoder.oc2.platform.parts

/**
 * Pure validation + map mutation for face-keyed [Part] storage. Used by the
 * AdapterBlockEntity (and any future PartHost BE) to relocate parts between
 * faces without going through the install→remove→re-create loop, which would
 * destroy per-instance state (label, channelId, accessSide, options, kind-
 * specific NBT).
 *
 * Lifecycle (channel registrants, [Part.onAttach] / [Part.onDetach], host
 * derivation) is intentionally *not* the helper's concern — those are MC-side
 * effects owned by the calling BE. The helper just answers "is this move
 * legal, and if so, swap the keys."
 *
 * Generic over the face key K so tests can use plain strings instead of
 * pulling `net.minecraft.core.Direction` into Rule-D-pure platform tests.
 */
object PartFaceMap {

    /**
     * Move the part at [from] to [to] in [parts]. Same [Part] instance survives
     * the move — caller is responsible for re-running [Part.onAttach] with a
     * host derived from [to] so capability lookups re-resolve against the new
     * neighbor.
     *
     * Returns true iff [parts] was mutated. Returns false (no-op) for:
     *   - [from] == [to] (nothing to do)
     *   - [from] empty (nothing to move)
     *   - [to] occupied (caller decided a swap-on-occupied behavior elsewhere)
     */
    fun <K> move(parts: MutableMap<K, Part>, from: K, to: K): Boolean {
        if (from == to) return false
        if (!parts.containsKey(from)) return false
        if (parts.containsKey(to)) return false
        parts[to] = parts.remove(from)!!
        return true
    }

    /**
     * Atomically swap the parts at [a] and [b] in [parts]. Both [Part] instances
     * survive — caller re-runs onAttach for each at its new face.
     *
     * Returns false (no-op) if [a] == [b] or either face is empty.
     */
    fun <K> swap(parts: MutableMap<K, Part>, a: K, b: K): Boolean {
        if (a == b) return false
        val partA = parts[a] ?: return false
        val partB = parts[b] ?: return false
        parts[a] = partB
        parts[b] = partA
        return true
    }
}
