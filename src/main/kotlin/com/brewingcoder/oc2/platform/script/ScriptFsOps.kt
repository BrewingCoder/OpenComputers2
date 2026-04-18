package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.os.PathResolver
import com.brewingcoder.oc2.platform.storage.MountPaths
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.nio.ByteBuffer

/**
 * Shared logic for the `fs` API exposed to both Lua and JS script hosts.
 * Each host's binding layer wraps these in its own VarArgFunction-equivalent;
 * the actual filesystem semantics live here so the two languages can't drift.
 *
 * All paths are resolved relative to [cwd] via [PathResolver], so scripts see
 * the same paths as the shell. Failures surface as [StorageException] (which
 * host bindings translate to LuaError / JavaScriptException).
 *
 * Deliberate v0 simplifications:
 *   - No file handles (no `fs.open`). `read`/`write`/`append` are whole-file ops.
 *   - No glob/pattern matching. User scripts filter [list] results themselves.
 *   - No recursive copy/move. `delete` is already recursive (mount-level).
 */
object ScriptFsOps {

    fun list(mount: WritableMount, cwd: String, path: String): List<String> {
        val resolved = PathResolver.resolve(cwd, path)
        if (resolved.isNotEmpty() && !mount.exists(resolved)) {
            throw StorageException("no such path: '$path'")
        }
        if (resolved.isNotEmpty() && !mount.isDirectory(resolved)) {
            throw StorageException("not a directory: '$path'")
        }
        return mount.list(resolved)
    }

    fun exists(mount: WritableMount, cwd: String, path: String): Boolean =
        mount.exists(PathResolver.resolve(cwd, path))

    fun isDir(mount: WritableMount, cwd: String, path: String): Boolean {
        val resolved = PathResolver.resolve(cwd, path)
        return mount.exists(resolved) && mount.isDirectory(resolved)
    }

    fun size(mount: WritableMount, cwd: String, path: String): Long {
        val resolved = PathResolver.resolve(cwd, path)
        if (!mount.exists(resolved)) throw StorageException("no such file: '$path'")
        return mount.size(resolved)
    }

    fun read(mount: WritableMount, cwd: String, path: String): String {
        val resolved = PathResolver.resolve(cwd, path)
        if (!mount.exists(resolved)) throw StorageException("no such file: '$path'")
        if (mount.isDirectory(resolved)) throw StorageException("is a directory: '$path'")
        mount.openForRead(resolved).use { ch ->
            val size = ch.size().toInt()
            val buf = ByteBuffer.allocate(size)
            while (buf.hasRemaining()) {
                val n = ch.read(buf)
                if (n < 0) break
            }
            return String(buf.array(), 0, buf.position(), Charsets.UTF_8)
        }
    }

    fun write(mount: WritableMount, cwd: String, path: String, text: String) {
        val resolved = PathResolver.resolve(cwd, path)
        if (resolved.isEmpty()) throw StorageException("cannot write to mount root")
        mount.openForWrite(resolved).use { ch ->
            val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buf.hasRemaining()) {
                val n = ch.write(buf)
                if (n <= 0) throw StorageException("out of space (${buf.remaining()} bytes unwritten)")
            }
        }
    }

    fun append(mount: WritableMount, cwd: String, path: String, text: String) {
        val resolved = PathResolver.resolve(cwd, path)
        if (resolved.isEmpty()) throw StorageException("cannot write to mount root")
        mount.openForAppend(resolved).use { ch ->
            val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buf.hasRemaining()) {
                val n = ch.write(buf)
                if (n <= 0) throw StorageException("out of space (${buf.remaining()} bytes unwritten)")
            }
        }
    }

    fun mkdir(mount: WritableMount, cwd: String, path: String) {
        val resolved = PathResolver.resolve(cwd, path)
        if (resolved.isEmpty()) throw StorageException("cannot create root")
        mount.makeDirectory(resolved)
    }

    fun delete(mount: WritableMount, cwd: String, path: String) {
        val resolved = PathResolver.resolve(cwd, path)
        if (resolved.isEmpty()) throw StorageException("cannot delete mount root")
        mount.delete(resolved)
    }

    fun capacity(mount: WritableMount): Long = mount.capacity()
    fun free(mount: WritableMount): Long = mount.remainingSpace()

    /** Normalize the name portion of a resolved path — used by `fs.name(path)` if we add it later. */
    fun basename(path: String): String = MountPaths.name(path)
}
