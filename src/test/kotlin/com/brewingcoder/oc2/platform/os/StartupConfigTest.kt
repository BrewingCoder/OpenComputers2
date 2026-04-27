package com.brewingcoder.oc2.platform.os

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StartupConfigTest {

    @Test
    fun `single line yields one entry with no args`() {
        StartupConfig.parse("reactor_ui.lua") shouldBe listOf(
            StartupConfig.Entry("reactor_ui.lua", emptyList())
        )
    }

    @Test
    fun `multiple lines yield ordered entries`() {
        val cfg = """
            reactor_ui.lua
            wifi_relay.lua main
        """.trimIndent()
        StartupConfig.parse(cfg) shouldBe listOf(
            StartupConfig.Entry("reactor_ui.lua", emptyList()),
            StartupConfig.Entry("wifi_relay.lua", listOf("main")),
        )
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val cfg = """
            # boot manifest

            reactor_ui.lua
            # legacy
            #wifi_relay.lua main

            ## still a comment
        """.trimIndent()
        StartupConfig.parse(cfg) shouldBe listOf(
            StartupConfig.Entry("reactor_ui.lua", emptyList())
        )
    }

    @Test
    fun `leading whitespace before comment is tolerated`() {
        StartupConfig.parse("    # indented comment\nreactor_ui.lua") shouldBe listOf(
            StartupConfig.Entry("reactor_ui.lua", emptyList())
        )
    }

    @Test
    fun `quoted args preserve spaces`() {
        StartupConfig.parse("""run.lua "hello world" plain""") shouldBe listOf(
            StartupConfig.Entry("run.lua", listOf("hello world", "plain"))
        )
    }

    @Test
    fun `empty file yields empty list`() {
        StartupConfig.parse("").shouldBeEmpty()
        StartupConfig.parse("   \n   \n").shouldBeEmpty()
        StartupConfig.parse("# only a comment\n").shouldBeEmpty()
    }

    @Test
    fun `entry invocation round-trips simple tokens unchanged`() {
        StartupConfig.Entry("reactor_ui.lua", emptyList()).invocation shouldBe "reactor_ui.lua"
        StartupConfig.Entry("foo.lua", listOf("a", "b")).invocation shouldBe "foo.lua a b"
    }

    @Test
    fun `entry invocation quotes args with spaces`() {
        StartupConfig.Entry("foo.lua", listOf("hello world")).invocation shouldBe "foo.lua \"hello world\""
    }
}
