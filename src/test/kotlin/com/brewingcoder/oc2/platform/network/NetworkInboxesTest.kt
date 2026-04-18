package com.brewingcoder.oc2.platform.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NetworkInboxesTest {

    @BeforeEach fun reset() = NetworkInboxes.resetForTest()
    @AfterEach fun cleanup() = NetworkInboxes.resetForTest()

    @Test
    fun `deliver then pop returns FIFO order`() {
        NetworkInboxes.deliver(7, NetworkInboxes.Message(from = 1, body = "first"))
        NetworkInboxes.deliver(7, NetworkInboxes.Message(from = 2, body = "second"))
        NetworkInboxes.size(7) shouldBe 2

        NetworkInboxes.pop(7)?.body shouldBe "first"
        NetworkInboxes.pop(7)?.body shouldBe "second"
        NetworkInboxes.pop(7) shouldBe null
        NetworkInboxes.size(7) shouldBe 0
    }

    @Test
    fun `pop on empty inbox returns null`() {
        NetworkInboxes.pop(99) shouldBe null
        NetworkInboxes.peek(99) shouldBe null
        NetworkInboxes.size(99) shouldBe 0
    }

    @Test
    fun `peek does not consume`() {
        NetworkInboxes.deliver(3, NetworkInboxes.Message(from = 1, body = "x"))
        NetworkInboxes.peek(3)?.body shouldBe "x"
        NetworkInboxes.size(3) shouldBe 1
        NetworkInboxes.peek(3)?.body shouldBe "x"
    }

    @Test
    fun `inbox cap drops oldest at INBOX_CAP overflow`() {
        repeat(NetworkInboxes.INBOX_CAP + 5) { i ->
            NetworkInboxes.deliver(1, NetworkInboxes.Message(from = 0, body = "msg-$i"))
        }
        NetworkInboxes.size(1) shouldBe NetworkInboxes.INBOX_CAP
        // oldest 5 (msg-0..msg-4) should have been dropped
        NetworkInboxes.pop(1)?.body shouldBe "msg-5"
    }

    @Test
    fun `oversized message is silently dropped`() {
        val big = "x".repeat(NetworkInboxes.MAX_MESSAGE_BYTES + 1)
        NetworkInboxes.deliver(1, NetworkInboxes.Message(from = 0, body = big))
        NetworkInboxes.size(1) shouldBe 0
    }

    @Test
    fun `inboxes are isolated per computerId`() {
        NetworkInboxes.deliver(1, NetworkInboxes.Message(from = 99, body = "to-1"))
        NetworkInboxes.deliver(2, NetworkInboxes.Message(from = 99, body = "to-2"))
        NetworkInboxes.size(1) shouldBe 1
        NetworkInboxes.size(2) shouldBe 1
        NetworkInboxes.pop(1)?.body shouldBe "to-1"
        NetworkInboxes.pop(2)?.body shouldBe "to-2"
    }

    @Test
    fun `clearAll wipes every inbox`() {
        NetworkInboxes.deliver(1, NetworkInboxes.Message(from = 0, body = "a"))
        NetworkInboxes.deliver(2, NetworkInboxes.Message(from = 0, body = "b"))
        NetworkInboxes.clearAll()
        NetworkInboxes.size(1) shouldBe 0
        NetworkInboxes.size(2) shouldBe 0
    }
}
