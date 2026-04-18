package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.StorageException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hands out monotonically increasing IDs per "kind", persisted to a flat text
 * file at `<world>/oc2/ids.json`. Mirrors CC:Tweaked's `IDAssigner` but
 * flat-text instead of JSON to keep zero deps.
 *
 * Two flavors of assignment:
 *   - [assign] — counter-only. Hands out a fresh id every call. Use when there's
 *     no stable identity (e.g. a transient handle).
 *   - [assignFor] — **idempotent by location key**. The same `(kind, locationKey)`
 *     pair always returns the same id, surviving NBT loss + crash recovery. Use
 *     for anything anchored to a stable identity like a block position.
 *
 * Why the location-keyed flavor exists (motivating bug, 2026-04-18):
 *   - BE.ensureComputerId assigned id 0, called setChanged()
 *   - World crashed before next autosave (within 5 min)
 *   - Next launch: BE loaded from stale NBT with no computerId → assigned id 1
 *   - Files at computer/0/ were orphaned; player's data appeared lost
 *
 * With [assignFor], even if BE NBT loses the id, the next assignFor() call with
 * the same blockPos retrieves the originally-assigned id from this file.
 * Side benefit: break + replace at the same coordinates reuses the same id, so
 * a player's files survive accidental block breaks. ("Permanent installation" feel.)
 *
 * File format (flat, one entry per line, `#` comments tolerated):
 *   - `next.<kind>=<int>` — next id to hand out for `kind`
 *   - `loc.<kind>.<locationKey>=<int>` — sticky assignment
 *   - Legacy: `<kind>=<int>` (read as `next.<kind>=<int>` for backward compat)
 *
 * Thread-safety: every public op is `@Synchronized` for the read-modify-flush cycle.
 */
class ComputerIdAssigner(private val path: Path) {

    private val nextIds: MutableMap<String, Int> = mutableMapOf()
    private val assignments: MutableMap<String, Int> = mutableMapOf()

    init {
        if (Files.exists(path)) {
            try {
                for (line in Files.readAllLines(path)) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val k = trimmed.substring(0, eq).trim()
                    val v = trimmed.substring(eq + 1).trim().toIntOrNull() ?: continue
                    when {
                        k.startsWith(NEXT_PREFIX) -> nextIds[k.substring(NEXT_PREFIX.length)] = v
                        k.startsWith(LOC_PREFIX) -> assignments[k.substring(LOC_PREFIX.length)] = v
                        // Legacy: bare `kind=int` is the old next-id format
                        else -> nextIds[k] = v
                    }
                }
            } catch (e: IOException) {
                throw StorageException("could not read id file: $path", e)
            }
        }
    }

    /**
     * Counter-only assignment. Returns a NEW id each call, increments the
     * persistent counter for [kind]. Use [assignFor] instead when the caller
     * has a stable identity to anchor against.
     */
    @Synchronized
    fun assign(kind: String): Int {
        val id = nextIds.getOrDefault(kind, 0)
        nextIds[kind] = id + 1
        save()
        return id
    }

    /**
     * Idempotent location-keyed assignment. The same [kind] + [locationKey]
     * always returns the same id, persisted across saves/crashes. New location
     * keys allocate a fresh id from the [kind] counter.
     */
    @Synchronized
    fun assignFor(kind: String, locationKey: String): Int {
        val key = "$kind.$locationKey"
        assignments[key]?.let { return it }
        val id = nextIds.getOrDefault(kind, 0)
        nextIds[kind] = id + 1
        assignments[key] = id
        save()
        return id
    }

    /** Read-only view of the next-id counters; tests + diagnostics. */
    @Synchronized
    fun snapshot(): Map<String, Int> = nextIds.toMap()

    /** Read-only view of sticky location-keyed assignments; tests + diagnostics. */
    @Synchronized
    fun assignmentsSnapshot(): Map<String, Int> = assignments.toMap()

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val sb = StringBuilder()
            sb.append("# next ids\n")
            nextIds.entries.sortedBy { it.key }.forEach { sb.append("$NEXT_PREFIX${it.key}=${it.value}\n") }
            if (assignments.isNotEmpty()) {
                sb.append("# sticky assignments\n")
                assignments.entries.sortedBy { it.key }.forEach { sb.append("$LOC_PREFIX${it.key}=${it.value}\n") }
            }
            Files.writeString(path, sb.toString())
        } catch (e: IOException) {
            throw StorageException("could not write id file: $path", e)
        }
    }

    companion object {
        private const val NEXT_PREFIX = "next."
        private const val LOC_PREFIX = "loc."
    }
}
