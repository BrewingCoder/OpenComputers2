package com.brewingcoder.oc2.platform.os

import com.brewingcoder.oc2.platform.storage.StorageException

/**
 * Resolves shell paths against a cwd. Mirrors the conventions used by the
 * underlying [com.brewingcoder.oc2.platform.storage.Mount] (no leading `/`,
 * empty string = root) but accepts user-friendly inputs.
 *
 * Rules:
 *   - `""` or `"."`  → cwd unchanged
 *   - leading `/`    → absolute (ignores cwd)
 *   - `..`           → up one level (rejected at root)
 *   - `.`            → ignored
 *   - trailing `/`   → ignored
 */
object PathResolver {

    fun resolve(cwd: String, path: String): String {
        if (path.isEmpty() || path == ".") return cwd
        val base = if (path.startsWith("/")) "" else cwd
        val combined = if (base.isEmpty()) path.trimStart('/') else "$base/${path.trimStart('/')}"
        val parts = mutableListOf<String>()
        for (segment in combined.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> { /* skip */ }
                segment == ".." -> {
                    if (parts.isEmpty()) throw StorageException("path escapes root: '$path'")
                    parts.removeAt(parts.size - 1)
                }
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }
}
