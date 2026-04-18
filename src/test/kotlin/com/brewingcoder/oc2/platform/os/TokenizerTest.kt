package com.brewingcoder.oc2.platform.os

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TokenizerTest {

    @Test
    fun `splits on whitespace`() {
        Tokenizer.tokenize("echo hello world") shouldBe listOf("echo", "hello", "world")
    }

    @Test
    fun `empty input yields empty list`() {
        Tokenizer.tokenize("") shouldBe emptyList()
        Tokenizer.tokenize("   ") shouldBe emptyList()
    }

    @Test
    fun `double-quoted string preserves spaces`() {
        Tokenizer.tokenize("""echo "hello world"""") shouldBe listOf("echo", "hello world")
    }

    @Test
    fun `single-quoted string preserves spaces and is literal`() {
        Tokenizer.tokenize("""echo 'a "b" c'""") shouldBe listOf("echo", """a "b" c""")
    }

    @Test
    fun `backslash escapes inside double quotes`() {
        Tokenizer.tokenize("""echo "she said \"hi\"" """) shouldBe listOf("echo", """she said "hi"""")
    }

    @Test
    fun `mixed quoted and unquoted args`() {
        Tokenizer.tokenize("""write foo.txt "line one"""") shouldBe listOf("write", "foo.txt", "line one")
    }

    @Test
    fun `empty quoted string is preserved as empty arg`() {
        Tokenizer.tokenize("""echo ""  rest""") shouldBe listOf("echo", "", "rest")
    }

    @Test
    fun `multiple internal spaces collapse`() {
        Tokenizer.tokenize("a    b\tc") shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        Tokenizer.tokenize("   ls   ") shouldBe listOf("ls")
        Tokenizer.tokenize("\t\tfoo bar\n") shouldBe listOf("foo", "bar")
    }

    @Test
    fun `unicode non-breaking spaces are treated as whitespace`() {
        // U+00A0 NBSP — macOS clipboard occasionally swaps these in
        Tokenizer.tokenize("\u00A0write\u00A0hi.txt") shouldBe listOf("write", "hi.txt")
        // U+2007 FIGURE SPACE
        Tokenizer.tokenize("ls\u2007foo") shouldBe listOf("ls", "foo")
        // U+202F NARROW NO-BREAK SPACE
        Tokenizer.tokenize("ls\u202Ffoo") shouldBe listOf("ls", "foo")
    }
}
