package com.brewingcoder.oc2.platform.storage

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Pure in-memory [WritableMount]. Three uses:
 *   1. unit-testing the [WritableMount] contract without touching the disk
 *   2. future per-computer `/tmp` ramdisk (wiped on reboot)
 *   3. fallback when the disk-backed store can't be created (e.g. read-only world)
 *
 * Capacity is checked on every write/append; over-capacity writes truncate to fit
 * and signal the shortfall via [remainingSpace] returning 0. The Lua/JS host is
 * responsible for surfacing `enospc`-style errors when it sees the buffer's
 * `position()` after `write()` advanced less than expected.
 *
 * Thread-safety: all public ops are `@Synchronized` on the mount instance. The
 * channels returned are NOT individually thread-safe but are synchronized when
 * they touch the backing map.
 */
class InMemoryMount(
    private val capacityBytes: Long = Long.MAX_VALUE,
) : WritableMount {

    private sealed interface Node
    private object Dir : Node
    private class FileNode(var data: ByteArray = EMPTY) : Node

    /** Path → Node. Root entry is `"" → Dir`. */
    private val nodes: MutableMap<String, Node> = mutableMapOf("" to Dir)

    @Synchronized
    override fun exists(path: String): Boolean = nodes.containsKey(MountPaths.normalize(path))

    @Synchronized
    override fun isDirectory(path: String): Boolean = nodes[MountPaths.normalize(path)] is Dir

    @Synchronized
    override fun list(path: String): List<String> {
        val key = MountPaths.normalize(path)
        val node = nodes[key] ?: throw StorageException("no such path: '$path'")
        if (node !is Dir) throw StorageException("not a directory: '$path'")
        val prefix = if (key.isEmpty()) "" else "$key/"
        return nodes.keys.asSequence()
            .filter { it.isNotEmpty() && it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .filter { !it.contains('/') }  // direct children only
            .sorted()
            .toList()
    }

    @Synchronized
    override fun size(path: String): Long {
        val node = nodes[MountPaths.normalize(path)] ?: throw StorageException("no such file: '$path'")
        return when (node) {
            is Dir -> 0L
            is FileNode -> node.data.size.toLong()
        }
    }

    @Synchronized
    override fun openForRead(path: String): SeekableByteChannel {
        val node = nodes[MountPaths.normalize(path)] ?: throw StorageException("no such file: '$path'")
        if (node !is FileNode) throw StorageException("not a file: '$path'")
        return ReadOnlyChannel(node.data)
    }

    @Synchronized
    override fun makeDirectory(path: String) {
        val key = MountPaths.normalize(path)
        if (key.isEmpty()) return  // root always exists
        when (val existing = nodes[key]) {
            is Dir -> return
            is FileNode -> throw StorageException("path is a file: '$path'")
            null -> {
                ensureParentDir(key)
                nodes[key] = Dir
            }
        }
    }

    @Synchronized
    override fun delete(path: String) {
        val key = MountPaths.normalize(path)
        if (key.isEmpty()) throw StorageException("cannot delete mount root")
        val node = nodes[key] ?: return
        if (node is Dir) {
            // recursive delete — drop everything under this prefix too
            val prefix = "$key/"
            val toRemove = nodes.keys.filter { it.startsWith(prefix) }
            for (k in toRemove) {
                val n = nodes[k]
                if (n is FileNode) usedBytes -= n.data.size
                nodes.remove(k)
            }
        } else if (node is FileNode) {
            usedBytes -= node.data.size
        }
        nodes.remove(key)
    }

    @Synchronized
    override fun rename(from: String, to: String) {
        val src = MountPaths.normalize(from)
        val dst = MountPaths.normalize(to)
        if (src.isEmpty()) throw StorageException("cannot rename mount root")
        val node = nodes[src] ?: throw StorageException("no such path: '$from'")
        if (nodes.containsKey(dst)) throw StorageException("destination exists: '$to'")
        ensureParentDir(dst)
        if (node is Dir) {
            val prefix = "$src/"
            val moved = nodes.entries.filter { it.key.startsWith(prefix) }
            for (e in moved) {
                nodes.remove(e.key)
                nodes[dst + "/" + e.key.substring(prefix.length)] = e.value
            }
        }
        nodes.remove(src)
        nodes[dst] = node
    }

    @Synchronized
    override fun openForWrite(path: String): SeekableByteChannel {
        val key = MountPaths.normalize(path)
        if (key.isEmpty()) throw StorageException("cannot write to mount root")
        ensureParentDir(key)
        when (val existing = nodes[key]) {
            is Dir -> throw StorageException("path is a directory: '$path'")
            is FileNode -> {
                usedBytes -= existing.data.size
                existing.data = EMPTY
                return WriteChannel(existing, position = 0L)
            }
            null -> {
                val node = FileNode()
                nodes[key] = node
                return WriteChannel(node, position = 0L)
            }
        }
    }

    @Synchronized
    override fun openForAppend(path: String): SeekableByteChannel {
        val key = MountPaths.normalize(path)
        if (key.isEmpty()) throw StorageException("cannot write to mount root")
        ensureParentDir(key)
        val node = when (val existing = nodes[key]) {
            is Dir -> throw StorageException("path is a directory: '$path'")
            is FileNode -> existing
            null -> FileNode().also { nodes[key] = it }
        }
        return WriteChannel(node, position = node.data.size.toLong())
    }

    override fun capacity(): Long = capacityBytes

    @Synchronized
    override fun remainingSpace(): Long = max(0L, capacityBytes - usedBytes)

    private var usedBytes: Long = 0L

    private fun ensureParentDir(key: String) {
        val parent = MountPaths.parent(key)
        if (parent.isEmpty()) return
        when (nodes[parent]) {
            is Dir -> return
            is FileNode -> throw StorageException("parent is a file: '$parent'")
            null -> {
                ensureParentDir(parent)
                nodes[parent] = Dir
            }
        }
    }

    /** Read-only seekable channel over a frozen byte array snapshot. */
    private class ReadOnlyChannel(private val data: ByteArray) : SeekableByteChannel {
        private var pos: Long = 0L
        private var open: Boolean = true

        override fun read(dst: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            if (pos >= data.size) return -1
            val n = min(dst.remaining(), data.size - pos.toInt())
            dst.put(data, pos.toInt(), n)
            pos += n
            return n
        }
        override fun write(src: ByteBuffer): Int = throw StorageException("read-only channel")
        override fun position(): Long = pos
        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0) { "negative position" }
            pos = newPosition
            return this
        }
        override fun size(): Long = data.size.toLong()
        override fun truncate(size: Long): SeekableByteChannel = throw StorageException("read-only channel")
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
    }

    /** Write/append channel. Touches the parent mount under its lock when growing the buffer. */
    private inner class WriteChannel(private val node: FileNode, position: Long) : SeekableByteChannel {
        private var pos: Long = position
        private var open: Boolean = true

        override fun read(dst: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            if (pos >= node.data.size) return -1
            val n = min(dst.remaining(), node.data.size - pos.toInt())
            dst.put(node.data, pos.toInt(), n)
            pos += n
            return n
        }

        override fun write(src: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            synchronized(this@InMemoryMount) {
                val want = src.remaining()
                val available = max(0L, capacityBytes - usedBytes)
                val n = min(want.toLong(), available).toInt()
                if (n <= 0) return 0
                val newSize = max(node.data.size.toLong(), pos + n).toInt()
                if (newSize > node.data.size) {
                    val grown = ByteArray(newSize)
                    System.arraycopy(node.data, 0, grown, 0, node.data.size)
                    usedBytes += (newSize - node.data.size)
                    node.data = grown
                }
                src.get(node.data, pos.toInt(), n)
                pos += n
                return n
            }
        }

        override fun position(): Long = pos
        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0) { "negative position" }
            pos = newPosition
            return this
        }
        override fun size(): Long = node.data.size.toLong()
        override fun truncate(size: Long): SeekableByteChannel {
            require(size >= 0) { "negative size" }
            synchronized(this@InMemoryMount) {
                if (size < node.data.size) {
                    val shrunk = ByteArray(size.toInt())
                    System.arraycopy(node.data, 0, shrunk, 0, shrunk.size)
                    usedBytes -= (node.data.size - size.toInt())
                    node.data = shrunk
                }
                if (pos > size) pos = size
            }
            return this
        }
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}
