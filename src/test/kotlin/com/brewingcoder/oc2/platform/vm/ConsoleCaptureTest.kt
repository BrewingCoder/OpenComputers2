package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import li.cil.sedna.api.device.serial.SerialDevice
import org.junit.jupiter.api.Test

class ConsoleCaptureTest {

    /** Replays a canned byte sequence through [SerialDevice.read]. */
    private class StubSerial(bytes: ByteArray) : SerialDevice {
        private val q = ArrayDeque<Int>().apply {
            for (b in bytes) addLast(b.toInt() and 0xFF)
        }
        override fun read(): Int = if (q.isEmpty()) -1 else q.removeFirst()
        override fun canPutByte(): Boolean = false
        override fun putByte(b: Byte) {}
    }

    @Test
    fun `drain pulls all available bytes into the ring`() {
        val serial = StubSerial("hello\n".toByteArray())
        val capture = ConsoleCapture(capacity = 64)

        capture.drain(serial)

        capture.byteCount shouldBe 6
        capture.snapshotString() shouldBe "hello\n"
    }

    @Test
    fun `drain on empty serial is a no-op`() {
        val capture = ConsoleCapture(capacity = 64)
        capture.drain(StubSerial(ByteArray(0)))
        capture.byteCount shouldBe 0
    }

    @Test
    fun `ring drops oldest bytes when full`() {
        val capture = ConsoleCapture(capacity = 4)
        capture.drain(StubSerial("ABCDEFG".toByteArray()))

        capture.byteCount shouldBe 4
        capture.snapshotString() shouldBe "DEFG"
    }

    @Test
    fun `recentLines returns most recent non-empty lines`() {
        val capture = ConsoleCapture(capacity = 64)
        capture.drain(StubSerial("alpha\nbeta\n\ngamma\n".toByteArray()))

        capture.recentLines(2) shouldContainInOrder listOf("beta", "gamma")
    }

    @Test
    fun `clear empties the ring`() {
        val capture = ConsoleCapture(capacity = 16)
        capture.drain(StubSerial("hi".toByteArray()))
        capture.byteCount shouldBe 2

        capture.clear()

        capture.byteCount shouldBe 0
        capture.snapshotString() shouldBe ""
    }
}
