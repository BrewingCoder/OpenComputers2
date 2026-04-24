package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.Mount
import com.brewingcoder.oc2.platform.storage.MountPaths
import com.brewingcoder.oc2.platform.storage.StorageException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min

/**
 * Read-only [Mount] backed by JAR resources under a fixed base path (e.g. `assets/oc2/rom`).
 *
 * At construction, walks every classpath URL that resolves to [basePath] and records every
 * child entry (file or directory) in a flat map keyed by mount-relative path. Walking covers
 * both jar (production) and exploded-directory (dev classpath) layouts. The tree is frozen
 * after `init` — JAR contents can't change at runtime, so there's no invalidation story.
 *
 * Bytes are read lazily on [openForRead] via [ClassLoader.getResourceAsStream]; the snapshot
 * is cached per-open in the channel. OC2 ROM is small (KB, not MB), so this is cheap and the
 * "data frozen at class-load" semantics are acceptable.
 */
class ResourceMount(
    private val basePath: String,
    private val classLoader: ClassLoader = ResourceMount::class.java.classLoader,
) : Mount {

    private val normalizedBase: String = basePath.trim('/')

    /** Relative-path → isDirectory. Root is `"" -> true`. Absent key means "doesn't exist". */
    private val entries: Map<String, Boolean> = scanClasspath()

    override fun exists(path: String): Boolean =
        entries.containsKey(MountPaths.normalize(path))

    override fun isDirectory(path: String): Boolean =
        entries[MountPaths.normalize(path)] == true

    override fun list(path: String): List<String> {
        val key = MountPaths.normalize(path)
        val isDir = entries[key] ?: throw StorageException("no such path: '$path'")
        if (!isDir) throw StorageException("not a directory: '$path'")
        val prefix = if (key.isEmpty()) "" else "$key/"
        return entries.keys.asSequence()
            .filter { it.isNotEmpty() && it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .filter { !it.contains('/') }  // direct children only
            .sorted()
            .toList()
    }

    override fun size(path: String): Long {
        val key = MountPaths.normalize(path)
        val isDir = entries[key] ?: throw StorageException("no such file: '$path'")
        if (isDir) return 0L
        return readBytes(key).size.toLong()
    }

    override fun openForRead(path: String): SeekableByteChannel {
        val key = MountPaths.normalize(path)
        val isDir = entries[key] ?: throw StorageException("no such file: '$path'")
        if (isDir) throw StorageException("not a file: '$path'")
        return ReadOnlyByteArrayChannel(readBytes(key))
    }

    private fun readBytes(key: String): ByteArray {
        val resource = if (key.isEmpty()) normalizedBase else "$normalizedBase/$key"
        val stream = classLoader.getResourceAsStream(resource)
            ?: throw StorageException("resource no longer available: '$key'")
        return stream.use { it.readBytes() }
    }

    private fun scanClasspath(): Map<String, Boolean> {
        val found = mutableMapOf<String, Boolean>()
        found[""] = true  // root always exists, even if the base path has no resources

        // Some classloaders need the trailing slash to enumerate directory URLs; try both.
        val candidates = mutableListOf<String>()
        if (normalizedBase.isNotEmpty()) {
            candidates += normalizedBase
            candidates += "$normalizedBase/"
        } else {
            candidates += ""
        }

        val seenUrls = mutableSetOf<String>()
        for (candidate in candidates) {
            val urls = classLoader.getResources(candidate).toList()
            for (url in urls) {
                val key = url.toString()
                if (!seenUrls.add(key)) continue
                scanUrl(url, found)
            }
        }
        return found.toMap()
    }

    private fun scanUrl(url: java.net.URL, out: MutableMap<String, Boolean>) {
        when (url.protocol) {
            "jar" -> scanJarUrl(url, out)
            "file" -> {
                val p = Paths.get(url.toURI())
                if (Files.exists(p)) scanFilePath(p, out)
            }
            else -> {
                // Unknown scheme (custom Neo classloader schemes like 'union://' land here).
                // Try FileSystems.newFileSystem as a generic fallback; if that fails, skip.
                try {
                    val fs = try {
                        FileSystems.getFileSystem(url.toURI())
                    } catch (_: FileSystemNotFoundException) {
                        FileSystems.newFileSystem(url.toURI(), emptyMap<String, Any>())
                    }
                    scanFilePath(fs.provider().getPath(url.toURI()), out)
                } catch (_: Exception) {
                    // Non-walkable URL kind; nothing we can do.
                }
            }
        }
    }

    private fun scanJarUrl(url: java.net.URL, out: MutableMap<String, Boolean>) {
        // "jar:file:/.../mod.jar!/assets/oc2/rom" OR ".../rom/"
        val urlStr = url.toString()
        val bangIdx = urlStr.indexOf("!/")
        if (bangIdx < 0) throw StorageException("malformed jar URL: $urlStr")
        val fsUri = URI(urlStr.substring(0, bangIdx + 1))
        val pathInJar = urlStr.substring(bangIdx + 1).trimEnd('/')
        val fs = try {
            FileSystems.getFileSystem(fsUri)
        } catch (_: FileSystemNotFoundException) {
            FileSystems.newFileSystem(fsUri, emptyMap<String, Any>())
        }
        scanFilePath(fs.getPath(if (pathInJar.isEmpty()) "/" else pathInJar), out)
    }

    private fun scanFilePath(base: Path, out: MutableMap<String, Boolean>) {
        if (!Files.exists(base)) return
        if (!Files.isDirectory(base)) return
        Files.walk(base).use { stream ->
            for (p in stream) {
                if (p == base) continue
                val rel = base.relativize(p).toString().replace('\\', '/').trimEnd('/')
                if (rel.isEmpty()) continue
                out[rel] = Files.isDirectory(p)
            }
        }
    }

    /** Read-only seekable channel over a frozen byte array snapshot. */
    private class ReadOnlyByteArrayChannel(private val data: ByteArray) : SeekableByteChannel {
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
}
