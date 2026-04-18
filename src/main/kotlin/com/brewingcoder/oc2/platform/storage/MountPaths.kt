package com.brewingcoder.oc2.platform.storage

/**
 * Mount-internal path utilities. All [Mount] / [WritableMount] implementations
 * normalize incoming paths through [normalize] so they share one set of rules:
 * leading/trailing slashes stripped, empty segments collapsed, `.` dropped,
 * and `..` rejected (no escaping the mount root).
 */
object MountPaths {
    fun normalize(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            throw StorageException("path traversal not allowed: '$path'")
        }
        return parts.joinToString("/")
    }

    /** Parent path, or `""` for root and top-level entries. */
    fun parent(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx < 0) "" else path.substring(0, idx)
    }

    /** Final segment of [path]. */
    fun name(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx < 0) path else path.substring(idx + 1)
    }

    /** Join [parent] and [child] with a `/`, handling empty parent (root). */
    fun join(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"
}
