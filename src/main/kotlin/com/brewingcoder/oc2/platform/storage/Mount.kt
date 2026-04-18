package com.brewingcoder.oc2.platform.storage

import java.nio.channels.SeekableByteChannel

/**
 * Read-only view of a virtual filesystem subtree mounted somewhere inside a computer's
 * file space. The shape mirrors CC:Tweaked's `dan200.computercraft.api.filesystem.Mount`
 * intentionally — we want driver authors who've worked on either mod to recognize the
 * surface immediately.
 *
 * Path conventions:
 *   - forward-slash separated, no leading `/`, no trailing `/`
 *   - empty string `""` is the mount root
 *   - `.` segments are stripped; `..` is rejected (no traversal out of the mount)
 *   - case sensitivity is impl-defined (disk-backed mounts inherit the host FS's behavior)
 *
 * All operations may throw [StorageException] for I/O failures, missing paths, or
 * type mismatches (e.g. listing a file). Callers that touch user input should
 * normalize via [MountPaths.normalize] first.
 *
 * Rule D: this interface lives in `platform/` and MUST NOT import any `net.minecraft.*`
 * type. Disk-backed and resource-backed implementations live in MC-coupled packages.
 */
interface Mount {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun list(path: String): List<String>
    fun size(path: String): Long
    fun openForRead(path: String): SeekableByteChannel
}
