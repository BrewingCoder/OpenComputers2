package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.MountPaths
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.deleteExisting
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.math.max

/**
 * On-disk [WritableMount]. Each computer gets one rooted at
 * `<world>/oc2/computer/<id>/` (resolved by [WorldStorageProvider]).
 *
 * Capacity: tracked across all bytes under [root]. Initial usage is computed by
 * walking the tree on construction; mutations adjust [usedBytes] incrementally.
 *
 * MC-coupled in name only — this class touches no `net.minecraft.*` types. The
 * coupling is geographic (lives outside `platform/` because it's instantiated by
 * MC-aware code). We could move it back into `platform/` later if useful.
 */
class WorldFileMount(
    private val root: Path,
    private val capacityBytes: Long,
) : WritableMount {

    init {
        Files.createDirectories(root)
    }

    private var usedBytes: Long = walkSize(root)

    override fun exists(path: String): Boolean = Files.exists(resolve(path))

    override fun isDirectory(path: String): Boolean = Files.isDirectory(resolve(path))

    override fun list(path: String): List<String> {
        val p = resolve(path)
        if (!Files.exists(p)) throw StorageException("no such path: '$path'")
        if (!p.isDirectory()) throw StorageException("not a directory: '$path'")
        return p.listDirectoryEntries().map { it.fileName.toString() }.sorted()
    }

    override fun size(path: String): Long {
        val p = resolve(path)
        if (!Files.exists(p)) throw StorageException("no such file: '$path'")
        if (p.isDirectory()) return 0L
        return p.fileSize()
    }

    override fun openForRead(path: String): SeekableByteChannel {
        val p = resolve(path)
        if (!Files.exists(p)) throw StorageException("no such file: '$path'")
        if (p.isDirectory()) throw StorageException("not a file: '$path'")
        return try {
            Files.newByteChannel(p, StandardOpenOption.READ)
        } catch (e: IOException) {
            throw StorageException("read failed: '$path'", e)
        }
    }

    override fun makeDirectory(path: String) {
        val p = resolve(path)
        if (Files.exists(p)) {
            if (!p.isDirectory()) throw StorageException("path is a file: '$path'")
            return
        }
        try {
            Files.createDirectories(p)
        } catch (e: IOException) {
            throw StorageException("mkdir failed: '$path'", e)
        }
    }

    override fun delete(path: String) {
        val key = MountPaths.normalize(path)
        if (key.isEmpty()) throw StorageException("cannot delete mount root")
        val p = resolve(key)
        if (!Files.exists(p)) return
        try {
            if (p.isDirectory()) {
                Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        usedBytes -= attrs.size()
                        file.deleteExisting()
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        dir.deleteExisting()
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                usedBytes -= p.fileSize()
                p.deleteExisting()
            }
        } catch (e: IOException) {
            throw StorageException("delete failed: '$path'", e)
        }
        if (usedBytes < 0) usedBytes = 0
    }

    override fun rename(from: String, to: String) {
        val src = resolve(from)
        val dst = resolve(to)
        if (!Files.exists(src)) throw StorageException("no such path: '$from'")
        if (Files.exists(dst)) throw StorageException("destination exists: '$to'")
        try {
            Files.createDirectories(dst.parent)
            Files.move(src, dst)
        } catch (e: IOException) {
            throw StorageException("rename failed: '$from' -> '$to'", e)
        }
    }

    override fun openForWrite(path: String): SeekableByteChannel = openWriteChannel(path, append = false)

    override fun openForAppend(path: String): SeekableByteChannel = openWriteChannel(path, append = true)

    private fun openWriteChannel(path: String, append: Boolean): SeekableByteChannel {
        val key = MountPaths.normalize(path)
        if (key.isEmpty()) throw StorageException("cannot write to mount root")
        val p = resolve(key)
        if (Files.isDirectory(p)) throw StorageException("path is a directory: '$path'")
        try {
            Files.createDirectories(p.parent)
            val priorSize = if (Files.exists(p)) p.fileSize() else 0L
            val opts = if (append) {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING)
            }
            val raw = Files.newByteChannel(p, *opts)
            val ch = if (append) raw.position(priorSize) else raw
            // truncating-open removed the prior bytes; reflect that in the budget
            if (!append) usedBytes -= priorSize
            return CapacityTrackingChannel(ch)
        } catch (e: IOException) {
            throw StorageException("write open failed: '$path'", e)
        }
    }

    override fun capacity(): Long = capacityBytes

    override fun remainingSpace(): Long = max(0L, capacityBytes - usedBytes)

    private fun resolve(path: String): Path {
        val normalized = MountPaths.normalize(path)
        return if (normalized.isEmpty()) root else root.resolve(normalized)
    }

    /** Wraps a channel; on every successful write delta, updates parent [usedBytes]. */
    private inner class CapacityTrackingChannel(private val inner: SeekableByteChannel) : SeekableByteChannel by inner {
        override fun write(src: java.nio.ByteBuffer): Int {
            val before = inner.size()
            val available = max(0L, capacityBytes - usedBytes)
            // truncate the buffer to what we can actually accept
            val want = src.remaining().toLong()
            if (want > available) {
                val savedLimit = src.limit()
                src.limit(src.position() + available.toInt())
                val n = inner.write(src)
                src.limit(savedLimit)
                usedBytes += max(0L, inner.size() - before)
                return n
            }
            val n = inner.write(src)
            usedBytes += max(0L, inner.size() - before)
            return n
        }

        override fun truncate(size: Long): SeekableByteChannel {
            val before = inner.size()
            inner.truncate(size)
            val after = inner.size()
            if (after < before) usedBytes -= (before - after)
            return this
        }
    }

    companion object {
        private fun walkSize(root: Path): Long {
            if (!Files.exists(root)) return 0L
            var total = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            })
            return total
        }
    }
}
