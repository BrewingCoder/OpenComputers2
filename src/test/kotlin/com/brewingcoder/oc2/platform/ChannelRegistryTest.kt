package com.brewingcoder.oc2.platform

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ChannelRegistry]. Uses [FakeRegistrant] (a plain data class)
 * to avoid pulling in BlockEntity / Mojang static state. This is exactly what
 * the Rule B refactor enables — see docs/11-engineering-rules.md.
 */
class ChannelRegistryTest {

    /** A minimal [ChannelRegistrant] for tests. Mutable channelId so we can simulate reassignment. */
    private class FakeRegistrant(
        override var channelId: String,
        override val location: Position = Position.ORIGIN,
    ) : ChannelRegistrant

    @BeforeEach
    fun resetRegistry() {
        ChannelRegistry.clearForTest()
    }

    @Test
    fun `register adds member to its channel`() {
        val a = FakeRegistrant("alpha")

        ChannelRegistry.register(a)

        ChannelRegistry.membersOf("alpha") shouldContain a
        ChannelRegistry.totalMembers() shouldBe 1
        ChannelRegistry.activeChannels() shouldBe setOf("alpha")
    }

    @Test
    fun `unregister removes member from its channel`() {
        val a = FakeRegistrant("alpha")
        ChannelRegistry.register(a)

        ChannelRegistry.unregister(a)

        ChannelRegistry.membersOf("alpha").shouldBeEmpty()
        ChannelRegistry.totalMembers() shouldBe 0
        ChannelRegistry.activeChannels().shouldBeEmpty()
    }

    @Test
    fun `multiple members on same channel coexist`() {
        val a = FakeRegistrant("alpha", Position(1, 0, 0))
        val b = FakeRegistrant("alpha", Position(2, 0, 0))
        val c = FakeRegistrant("alpha", Position(3, 0, 0))

        ChannelRegistry.register(a)
        ChannelRegistry.register(b)
        ChannelRegistry.register(c)

        ChannelRegistry.membersOf("alpha") shouldContainExactlyInAnyOrder setOf(a, b, c)
        ChannelRegistry.totalMembers() shouldBe 3
    }

    @Test
    fun `members on different channels don't see each other`() {
        val onAlpha = FakeRegistrant("alpha")
        val onBeta = FakeRegistrant("beta")
        val alsoBeta = FakeRegistrant("beta")

        ChannelRegistry.register(onAlpha)
        ChannelRegistry.register(onBeta)
        ChannelRegistry.register(alsoBeta)

        ChannelRegistry.membersOf("alpha") shouldContainExactlyInAnyOrder setOf(onAlpha)
        ChannelRegistry.membersOf("beta") shouldContainExactlyInAnyOrder setOf(onBeta, alsoBeta)
        ChannelRegistry.activeChannels() shouldBe setOf("alpha", "beta")
        ChannelRegistry.totalMembers() shouldBe 3
    }

    @Test
    fun `re-registering same member is idempotent`() {
        val a = FakeRegistrant("alpha")

        ChannelRegistry.register(a)
        ChannelRegistry.register(a)
        ChannelRegistry.register(a)

        ChannelRegistry.membersOf("alpha") shouldHaveSize 1
        ChannelRegistry.totalMembers() shouldBe 1
    }

    @Test
    fun `unregistering a never-registered member is a no-op`() {
        val a = FakeRegistrant("alpha")

        ChannelRegistry.unregister(a)  // must not throw

        ChannelRegistry.totalMembers() shouldBe 0
    }

    @Test
    fun `channel reassignment via unregister-then-register moves the member cleanly`() {
        val a = FakeRegistrant("alpha")
        ChannelRegistry.register(a)

        // simulate the production setChannel() pattern:
        ChannelRegistry.unregister(a)
        a.channelId = "beta"
        ChannelRegistry.register(a)

        ChannelRegistry.membersOf("alpha").shouldBeEmpty()
        ChannelRegistry.membersOf("beta") shouldContain a
        ChannelRegistry.totalMembers() shouldBe 1
        ChannelRegistry.activeChannels() shouldBe setOf("beta")
    }

    @Test
    fun `empty channel is removed from activeChannels after last member leaves`() {
        val a = FakeRegistrant("alpha")
        val b = FakeRegistrant("alpha")
        ChannelRegistry.register(a)
        ChannelRegistry.register(b)

        ChannelRegistry.activeChannels() shouldContain "alpha"

        ChannelRegistry.unregister(a)
        ChannelRegistry.activeChannels() shouldContain "alpha"  // b still there

        ChannelRegistry.unregister(b)
        ChannelRegistry.activeChannels() shouldNotContain "alpha"
    }

    @Test
    fun `membersOf returns snapshot — mutating registry afterwards doesn't affect it`() {
        val a = FakeRegistrant("alpha")
        val b = FakeRegistrant("alpha")
        ChannelRegistry.register(a)
        ChannelRegistry.register(b)

        val snapshot = ChannelRegistry.membersOf("alpha")
        ChannelRegistry.unregister(a)

        // snapshot must still contain both — it's a frozen view, not a live one
        snapshot shouldContainExactlyInAnyOrder setOf(a, b)
        ChannelRegistry.membersOf("alpha") shouldContainExactlyInAnyOrder setOf(b)
    }

    @Test
    fun `totalMembers counts across all channels`() {
        val r1 = FakeRegistrant("a"); val r2 = FakeRegistrant("a")
        val r3 = FakeRegistrant("b")
        val r4 = FakeRegistrant("c"); val r5 = FakeRegistrant("c"); val r6 = FakeRegistrant("c")

        listOf(r1, r2, r3, r4, r5, r6).forEach { ChannelRegistry.register(it) }

        ChannelRegistry.totalMembers() shouldBe 6
    }
}
