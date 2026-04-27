package com.brewingcoder.oc2.platform.vm

import li.cil.sedna.api.device.serial.SerialDevice

/**
 * Bounded byte ring that drains a [SerialDevice] (typically the Control Plane's
 * UART16550A standard-output device) into an in-memory buffer.
 *
 * Once the ring fills, [drain] keeps consuming bytes from the serial device but
 * silently drops the oldest ones — what matters at the BE level is "what did
 * the kernel print recently", not a complete log. Persistent log shipping is a
 * separate (later) commit.
 *
 * Pure Rule-D code — testable without a full Sedna board, and decoupled from
 * the specific serial device implementation.
 */
class ConsoleCapture(val capacity: Int = DEFAULT_CAPACITY) {

    private val ring = ByteArray(capacity)
    private var head: Int = 0
    private var size: Int = 0

    /** Bytes currently buffered. */
    val byteCount: Int get() = size

    /** Pull every byte the device has queued for the host. Idempotent when empty. */
    fun drain(serial: SerialDevice) {
        while (true) {
            val b = serial.read()
            if (b < 0) return
            put(b.toByte())
        }
    }

    /** Append a single byte directly. Mainly for tests; production uses [drain]. */
    fun put(b: Byte) {
        if (size < capacity) {
            ring[(head + size) % capacity] = b
            size++
        } else {
            ring[head] = b
            head = (head + 1) % capacity
        }
    }

    /** Flatten the ring back into a byte array, oldest first. */
    fun snapshotBytes(): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = ring[(head + i) % capacity]
        return out
    }

    /** UTF-8 decode of the current ring contents. Replaces invalid sequences. */
    fun snapshotString(): String = snapshotBytes().toString(Charsets.UTF_8)

    /** Last [maxLines] non-empty lines. Useful for status surfaces. */
    fun recentLines(maxLines: Int): List<String> =
        snapshotString()
            .split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .takeLast(maxLines)

    /** Wipe the buffer. Doesn't shrink the backing array. */
    fun clear() {
        head = 0
        size = 0
    }

    companion object {
        /** 4 KB — enough for the boot banner + a few kernel log lines. */
        const val DEFAULT_CAPACITY: Int = 4 * 1024
    }
}
