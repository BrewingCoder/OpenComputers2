package com.brewingcoder.oc2.platform.storage

import java.nio.channels.SeekableByteChannel

/**
 * Read-write extension of [Mount]. Adds the operations the VM's filesystem API
 * needs to back `io.open(...)`, `fs.makeDir`, `fs.delete`, `fs.move`, etc.
 *
 * Capacity model: every writable mount has a fixed [capacity] in bytes; [remainingSpace]
 * reports what's left after on-disk overhead. The Lua/JS hosts use these to back
 * `fs.getCapacity` / `fs.getFreeSpace`. Per-computer mounts get a config-driven cap;
 * in-memory and unbounded test mounts return [Long.MAX_VALUE].
 */
interface WritableMount : Mount {
    fun makeDirectory(path: String)
    fun delete(path: String)
    fun rename(from: String, to: String)
    fun openForWrite(path: String): SeekableByteChannel
    fun openForAppend(path: String): SeekableByteChannel
    fun capacity(): Long
    fun remainingSpace(): Long
}
