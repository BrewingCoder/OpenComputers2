package com.brewingcoder.oc2.platform.control

import com.brewingcoder.oc2.platform.storage.StorageException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Per-world ownership map for Tier-2 Control Plane blocks. Tracks which player
 * owns the (at most one) Control Plane block in this world, and where it is.
 *
 * Persists to `<world>/oc2/control-planes.txt` as flat text — same shape as
 * [com.brewingcoder.oc2.storage.ComputerIdAssigner], so a server admin can
 * inspect and edit by hand if a Control Plane gets orphaned.
 *
 * File format (one entry per line, `#` comments tolerated):
 * ```
 * <player-uuid>=<dimension-resource-location>:<x>:<y>:<z>
 * ```
 *
 * Mirrors `OpenComputers2Registry` discipline but for ownership rather than
 * channel discovery. Lifetime-managed by `OC2ServerContext` — bound to a
 * specific server, dropped on `ServerStoppingEvent`.
 *
 * Pure Rule-D code: no `net.minecraft.*` imports — `Location` is a plain
 * data class that callers construct from a [net.minecraft.core.GlobalPos] /
 * [net.minecraft.resources.ResourceKey] on the MC side. Keeps this testable
 * without a server fixture.
 *
 * Thread-safety: every public mutating op is `@Synchronized` (place/break
 * happen on the server thread, but reflection probes from oc2-debug or
 * future async surfaces would otherwise race on the maps).
 */
class ControlPlaneRegistry(private val path: Path) {

    /** Stable, dimension-aware location of a Control Plane in the world. */
    data class Location(
        val dimension: String,
        val x: Int,
        val y: Int,
        val z: Int,
    ) {
        fun encode(): String = "$dimension:$x:$y:$z"

        companion object {
            fun decodeOrNull(s: String): Location? {
                // `mc:dim:x:y:z` — split from the right so dimension can contain colons
                val parts = s.split(':')
                if (parts.size < 4) return null
                val z = parts[parts.size - 1].toIntOrNull() ?: return null
                val y = parts[parts.size - 2].toIntOrNull() ?: return null
                val x = parts[parts.size - 3].toIntOrNull() ?: return null
                val dim = parts.subList(0, parts.size - 3).joinToString(":")
                return Location(dim, x, y, z)
            }
        }
    }

    private val byOwner: MutableMap<UUID, Location> = mutableMapOf()
    private val byLocation: MutableMap<Location, UUID> = mutableMapOf()

    init {
        if (Files.exists(path)) {
            try {
                for (line in Files.readAllLines(path)) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val ownerStr = trimmed.substring(0, eq).trim()
                    val locStr = trimmed.substring(eq + 1).trim()
                    val owner = runCatching { UUID.fromString(ownerStr) }.getOrNull() ?: continue
                    val loc = Location.decodeOrNull(locStr) ?: continue
                    byOwner[owner] = loc
                    byLocation[loc] = owner
                }
            } catch (e: IOException) {
                throw StorageException("could not read control-plane registry: $path", e)
            }
        }
    }

    /** True if [owner] already has a Control Plane registered. */
    @Synchronized
    fun hasOwner(owner: UUID): Boolean = byOwner.containsKey(owner)

    /** Location of [owner]'s Control Plane, or null if they don't own one. */
    @Synchronized
    fun locationFor(owner: UUID): Location? = byOwner[owner]

    /** Owner of the Control Plane at [location], or null if no one owns it. */
    @Synchronized
    fun ownerAt(location: Location): UUID? = byLocation[location]

    /**
     * Register [owner]'s Control Plane at [location]. Returns true if the
     * registration succeeded; false if [owner] already owns one elsewhere
     * (caller should reject the placement).
     *
     * Idempotent — re-registering the same `(owner, location)` pair returns
     * true and is a no-op.
     */
    @Synchronized
    fun assign(owner: UUID, location: Location): Boolean {
        val existing = byOwner[owner]
        if (existing != null && existing != location) return false
        // If something else was at this location (shouldn't happen, but be safe), evict it.
        byLocation[location]?.let { prev -> if (prev != owner) byOwner.remove(prev) }
        byOwner[owner] = location
        byLocation[location] = owner
        save()
        return true
    }

    /** Drop [owner]'s registration. Returns true if there was one to drop. */
    @Synchronized
    fun release(owner: UUID): Boolean {
        val loc = byOwner.remove(owner) ?: return false
        byLocation.remove(loc)
        save()
        return true
    }

    /** Drop the registration at [location]. Returns true if there was one to drop. */
    @Synchronized
    fun releaseAt(location: Location): Boolean {
        val owner = byLocation.remove(location) ?: return false
        byOwner.remove(owner)
        save()
        return true
    }

    /** Read-only snapshot of all registered owners → locations. */
    @Synchronized
    fun snapshot(): Map<UUID, Location> = byOwner.toMap()

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val sb = StringBuilder()
            sb.append("# Tier-2 Control Plane ownership — one entry per player\n")
            sb.append("# format: <player-uuid>=<dimension>:<x>:<y>:<z>\n")
            byOwner.entries.sortedBy { it.key.toString() }.forEach { (owner, loc) ->
                sb.append("$owner=${loc.encode()}\n")
            }
            Files.writeString(path, sb.toString())
        } catch (e: IOException) {
            throw StorageException("could not write control-plane registry: $path", e)
        }
    }
}
