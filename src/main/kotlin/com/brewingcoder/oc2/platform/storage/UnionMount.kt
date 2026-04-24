package com.brewingcoder.oc2.platform.storage

import java.nio.channels.SeekableByteChannel

/**
 * Composite [WritableMount] that overlays a read-only [rom] mount at a reserved
 * [romPrefix] (default `"rom"`) on top of a [primary] writable mount. Scripts see:
 *
 *   - `/foo`, `/bar` etc.  → backed by [primary] (writable)
 *   - `/rom/...`           → backed by [rom] (read-only; writes throw)
 *
 * The prefix is strict — anything under `rom/` is ALWAYS routed to the ROM, even
 * if the user somehow wrote into primary at that path. `rom` is appended as a
 * synthetic directory entry when listing the root so `fs.list("/")` shows it.
 *
 * Writes and mutations that target the ROM prefix throw [StorageException]. This
 * is the seam that lets the computer see in-JAR boot scripts / libraries at
 * `/rom/lib/` entries while keeping the per-computer writable root untouched.
 *
 * Rule D: pure Kotlin, no MC imports — tests can compose this with [InMemoryMount]
 * on both sides without pulling in the resource loader.
 */
class UnionMount(
    private val primary: WritableMount,
    private val rom: Mount,
    private val romPrefix: String = "rom",
) : WritableMount {

    init {
        require(romPrefix.isNotEmpty() && !romPrefix.contains('/')) {
            "romPrefix must be a single non-empty path segment, got '$romPrefix'"
        }
    }

    override fun exists(path: String): Boolean {
        val n = MountPaths.normalize(path)
        if (n.isEmpty()) return true
        return if (isRom(n)) rom.exists(stripRom(n)) else primary.exists(n)
    }

    override fun isDirectory(path: String): Boolean {
        val n = MountPaths.normalize(path)
        if (n.isEmpty()) return true
        return if (isRom(n)) rom.isDirectory(stripRom(n)) else primary.isDirectory(n)
    }

    override fun list(path: String): List<String> {
        val n = MountPaths.normalize(path)
        if (n.isEmpty()) {
            val out = sortedSetOf<String>()
            out.addAll(primary.list(""))
            out.add(romPrefix)  // synthetic entry for the ROM overlay
            return out.toList()
        }
        return if (isRom(n)) rom.list(stripRom(n)) else primary.list(n)
    }

    override fun size(path: String): Long {
        val n = MountPaths.normalize(path)
        if (n.isEmpty()) return 0L
        return if (isRom(n)) rom.size(stripRom(n)) else primary.size(n)
    }

    override fun openForRead(path: String): SeekableByteChannel {
        val n = MountPaths.normalize(path)
        if (n.isEmpty()) throw StorageException("not a file: '$path'")
        return if (isRom(n)) rom.openForRead(stripRom(n)) else primary.openForRead(n)
    }

    override fun makeDirectory(path: String) {
        val n = MountPaths.normalize(path)
        if (isRom(n)) throw StorageException("read-only: '/$romPrefix' is the ROM overlay")
        primary.makeDirectory(n)
    }

    override fun delete(path: String) {
        val n = MountPaths.normalize(path)
        if (isRom(n)) throw StorageException("read-only: '/$romPrefix' is the ROM overlay")
        primary.delete(n)
    }

    override fun rename(from: String, to: String) {
        val a = MountPaths.normalize(from)
        val b = MountPaths.normalize(to)
        if (isRom(a) || isRom(b)) throw StorageException("read-only: '/$romPrefix' is the ROM overlay")
        primary.rename(a, b)
    }

    override fun openForWrite(path: String): SeekableByteChannel {
        val n = MountPaths.normalize(path)
        if (isRom(n)) throw StorageException("read-only: '/$romPrefix' is the ROM overlay")
        return primary.openForWrite(n)
    }

    override fun openForAppend(path: String): SeekableByteChannel {
        val n = MountPaths.normalize(path)
        if (isRom(n)) throw StorageException("read-only: '/$romPrefix' is the ROM overlay")
        return primary.openForAppend(n)
    }

    override fun capacity(): Long = primary.capacity()

    override fun remainingSpace(): Long = primary.remainingSpace()

    private fun isRom(normalized: String): Boolean =
        normalized == romPrefix || normalized.startsWith("$romPrefix/")

    private fun stripRom(normalized: String): String =
        if (normalized == romPrefix) "" else normalized.substring(romPrefix.length + 1)
}
