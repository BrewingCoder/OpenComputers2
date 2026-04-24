package com.brewingcoder.oc2.platform.http

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UrlAllowlistTest {

    // ---- pastebinRaw ----

    @Test
    fun `pastebinRaw builds the canonical raw URL for a valid key`() {
        UrlAllowlist.pastebinRaw("abc12345") shouldBe "https://pastebin.com/raw/abc12345"
    }

    @Test
    fun `pastebinRaw accepts alphanumeric mixed-case keys`() {
        UrlAllowlist.pastebinRaw("AbCd1234") shouldBe "https://pastebin.com/raw/AbCd1234"
    }

    @Test
    fun `pastebinRaw rejects empty key`() {
        UrlAllowlist.pastebinRaw("").shouldBeNull()
    }

    @Test
    fun `pastebinRaw rejects path separators`() {
        UrlAllowlist.pastebinRaw("abc/../etc").shouldBeNull()
        UrlAllowlist.pastebinRaw("abc/def").shouldBeNull()
        UrlAllowlist.pastebinRaw("..").shouldBeNull()
    }

    @Test
    fun `pastebinRaw rejects query-like strings`() {
        UrlAllowlist.pastebinRaw("abc?code=x").shouldBeNull()
        UrlAllowlist.pastebinRaw("abc#frag").shouldBeNull()
    }

    @Test
    fun `pastebinRaw rejects URL encoding`() {
        UrlAllowlist.pastebinRaw("abc%2Fetc").shouldBeNull()
    }

    @Test
    fun `pastebinRaw rejects a full URL pretending to be a key`() {
        UrlAllowlist.pastebinRaw("http://evil.com/paste/xyz").shouldBeNull()
        UrlAllowlist.pastebinRaw("evil.com/paste/xyz").shouldBeNull()
    }

    @Test
    fun `pastebinRaw rejects whitespace`() {
        UrlAllowlist.pastebinRaw("abc def").shouldBeNull()
        UrlAllowlist.pastebinRaw(" abc").shouldBeNull()
        UrlAllowlist.pastebinRaw("abc\n").shouldBeNull()
    }

    @Test
    fun `pastebinRaw rejects overlong keys`() {
        UrlAllowlist.pastebinRaw("a".repeat(65)).shouldBeNull()
    }

    // ---- gistApi ----

    @Test
    fun `gistApi builds the canonical metadata URL`() {
        UrlAllowlist.gistApi("deadbeef1234567890abcdef12345678") shouldBe
            "https://api.github.com/gists/deadbeef1234567890abcdef12345678"
    }

    @Test
    fun `gistApi rejects path traversal`() {
        UrlAllowlist.gistApi("../secrets").shouldBeNull()
        UrlAllowlist.gistApi("a/b").shouldBeNull()
    }

    @Test
    fun `gistApi rejects query strings`() {
        UrlAllowlist.gistApi("abc?tok=x").shouldBeNull()
    }

    // ---- gistRawOnAllowedHost ----

    @Test
    fun `gistRawOnAllowedHost passes through a legit raw_url`() {
        val u = "https://gist.githubusercontent.com/user/id/raw/sha/file.lua"
        UrlAllowlist.gistRawOnAllowedHost(u) shouldBe u
    }

    @Test
    fun `gistRawOnAllowedHost rejects off-host URLs`() {
        UrlAllowlist.gistRawOnAllowedHost("https://evil.com/foo.lua").shouldBeNull()
        UrlAllowlist.gistRawOnAllowedHost("https://raw.githubusercontent.com/foo.lua").shouldBeNull()
        UrlAllowlist.gistRawOnAllowedHost("http://gist.githubusercontent.com/foo.lua").shouldBeNull()
    }

    @Test
    fun `gistRawOnAllowedHost rejects query and fragment`() {
        UrlAllowlist.gistRawOnAllowedHost("https://gist.githubusercontent.com/f?a=1").shouldBeNull()
        UrlAllowlist.gistRawOnAllowedHost("https://gist.githubusercontent.com/f#x").shouldBeNull()
    }

    // ---- isSafeToken ----

    @Test
    fun `isSafeToken boundary cases`() {
        UrlAllowlist.isSafeToken("a") shouldBe true
        UrlAllowlist.isSafeToken("0") shouldBe true
        UrlAllowlist.isSafeToken("a".repeat(64)) shouldBe true
        UrlAllowlist.isSafeToken("a".repeat(65)) shouldBe false
        UrlAllowlist.isSafeToken("") shouldBe false
    }
}
