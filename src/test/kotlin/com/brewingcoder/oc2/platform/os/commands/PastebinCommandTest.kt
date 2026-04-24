package com.brewingcoder.oc2.platform.os.commands

import com.brewingcoder.oc2.platform.http.FetchResult
import com.brewingcoder.oc2.platform.http.HttpFetcher
import com.brewingcoder.oc2.platform.http.NetworkFetchPolicy
import com.brewingcoder.oc2.platform.os.Shell
import com.brewingcoder.oc2.platform.os.ShellMetadata
import com.brewingcoder.oc2.platform.os.ShellSession
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.ByteBuffer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the pastebin + gist shell commands with a mock [HttpFetcher].
 * Covers: happy-path fetch → mount, allowlist rejection, disabled flag,
 * gist file picking, off-host raw_url rejection.
 */
class PastebinCommandTest {

    private lateinit var mount: WritableMount
    private lateinit var session: ShellSession

    @BeforeEach
    fun setup() {
        // Reset policy between tests.
        NetworkFetchPolicy.allowPastebinFetch = true
        NetworkFetchPolicy.allowGistFetch = true
        NetworkFetchPolicy.maxFetchBytes = 1024 * 1024
        NetworkFetchPolicy.fetchTimeoutSeconds = 15
        mount = InMemoryMount(capacityBytes = 64 * 1024L)
        session = ShellSession(
            mount = mount,
            metadataProvider = { ShellMetadata(computerId = 1, channelId = "test", location = "(0,0,0)") },
        )
    }

    private fun shellWith(fetcher: HttpFetcher): Shell {
        val commands = listOf(
            PastebinCommand(fetcher),
            GistCommand(fetcher),
        )
        return Shell(commands)
    }

    private fun readFile(path: String): String {
        return mount.openForRead(path).use { ch ->
            val buf = ByteBuffer.allocate(ch.size().toInt())
            while (buf.hasRemaining()) { val n = ch.read(buf); if (n < 0) break }
            String(buf.array(), 0, buf.position(), Charsets.UTF_8)
        }
    }

    // ---- pastebin ----

    @Test
    fun `pastebin get writes fetched body to default path`() {
        val fetcher = HttpFetcher { url, _, _ ->
            url shouldBe "https://pastebin.com/raw/abc123"
            FetchResult.Ok("print('hi')".toByteArray(), "text/plain")
        }
        val r = shellWith(fetcher).execute("pastebin get abc123", session)
        r.exitCode shouldBe 0
        readFile("abc123.lua") shouldBe "print('hi')"
    }

    @Test
    fun `pastebin get with explicit outfile uses that path`() {
        val fetcher = HttpFetcher { _, _, _ -> FetchResult.Ok("body".toByteArray(), null) }
        val r = shellWith(fetcher).execute("pastebin get xyz99 mine.lua", session)
        r.exitCode shouldBe 0
        readFile("mine.lua") shouldBe "body"
    }

    @Test
    fun `pastebin get rejects a malformed key without touching the fetcher`() {
        var called = false
        val fetcher = HttpFetcher { _, _, _ -> called = true; FetchResult.Err(FetchResult.Err.Kind.NETWORK, "nope") }
        val r = shellWith(fetcher).execute("pastebin get abc/../etc", session)
        r.exitCode shouldBe 1
        called shouldBe false
        r.lines.joinToString("\n") shouldContain "not a valid paste key"
    }

    @Test
    fun `pastebin refuses when disabled by config`() {
        NetworkFetchPolicy.allowPastebinFetch = false
        val fetcher = HttpFetcher { _, _, _ -> FetchResult.Ok("x".toByteArray(), null) }
        val r = shellWith(fetcher).execute("pastebin get abc", session)
        r.exitCode shouldBe 1
        r.lines.joinToString("\n") shouldContain "disabled by server config"
    }

    @Test
    fun `pastebin propagates http errors`() {
        val fetcher = HttpFetcher { _, _, _ ->
            FetchResult.Err(FetchResult.Err.Kind.HTTP_STATUS, "HTTP 404")
        }
        val r = shellWith(fetcher).execute("pastebin get abc", session)
        r.exitCode shouldBe 1
        r.lines.joinToString("\n") shouldContain "HTTP 404"
    }

    @Test
    fun `pastebin prints usage with no args`() {
        val fetcher = HttpFetcher { _, _, _ -> error("should not be called") }
        val r = shellWith(fetcher).execute("pastebin", session)
        r.exitCode shouldBe 2
        r.lines.joinToString("\n") shouldContain "usage:"
    }

    // ---- gist ----

    @Test
    fun `gist get picks the first lua file by default`() {
        val meta = """
          {
            "files": {
              "z.js":  { "filename": "z.js",  "raw_url": "https://gist.githubusercontent.com/u/i/raw/s/z.js" },
              "a.lua": { "filename": "a.lua", "raw_url": "https://gist.githubusercontent.com/u/i/raw/s/a.lua" },
              "b.lua": { "filename": "b.lua", "raw_url": "https://gist.githubusercontent.com/u/i/raw/s/b.lua" }
            }
          }
        """.trimIndent()
        val fetcher = HttpFetcher { url, _, _ ->
            when {
                url.startsWith("https://api.github.com/gists/") -> FetchResult.Ok(meta.toByteArray(), "application/json")
                url.endsWith("/a.lua") -> FetchResult.Ok("-- a.lua body".toByteArray(), "text/plain")
                else -> error("unexpected url: $url")
            }
        }
        val r = shellWith(fetcher).execute("gist get deadbeef", session)
        r.exitCode shouldBe 0
        readFile("a.lua") shouldBe "-- a.lua body"
    }

    @Test
    fun `gist get with explicit file selects that one`() {
        val meta = """
          { "files": {
              "a.lua": { "filename": "a.lua", "raw_url": "https://gist.githubusercontent.com/u/i/raw/s/a.lua" },
              "b.lua": { "filename": "b.lua", "raw_url": "https://gist.githubusercontent.com/u/i/raw/s/b.lua" }
            } }
        """.trimIndent()
        val fetcher = HttpFetcher { url, _, _ ->
            when {
                url.startsWith("https://api.github.com/gists/") -> FetchResult.Ok(meta.toByteArray(), "application/json")
                url.endsWith("/b.lua") -> FetchResult.Ok("B".toByteArray(), "text/plain")
                else -> error("unexpected url: $url")
            }
        }
        val r = shellWith(fetcher).execute("gist get deadbeef b.lua", session)
        r.exitCode shouldBe 0
        readFile("b.lua") shouldBe "B"
    }

    @Test
    fun `gist rejects raw_url on a non-allowlisted host`() {
        val meta = """
          { "files": { "x.lua": { "filename": "x.lua", "raw_url": "https://evil.example.com/steal.lua" } } }
        """.trimIndent()
        val fetcher = HttpFetcher { url, _, _ ->
            if (url.startsWith("https://api.github.com/gists/")) FetchResult.Ok(meta.toByteArray(), "application/json")
            else error("should not fetch off-host: $url")
        }
        val r = shellWith(fetcher).execute("gist get deadbeef", session)
        r.exitCode shouldBe 1
        r.lines.joinToString("\n") shouldContain "not allowlisted"
    }

    @Test
    fun `gist refuses when disabled by config`() {
        NetworkFetchPolicy.allowGistFetch = false
        val fetcher = HttpFetcher { _, _, _ -> FetchResult.Ok("x".toByteArray(), null) }
        val r = shellWith(fetcher).execute("gist get abc", session)
        r.exitCode shouldBe 1
    }

    @Test
    fun `gist rejects a malformed id without touching the fetcher`() {
        var called = false
        val fetcher = HttpFetcher { _, _, _ -> called = true; FetchResult.Ok("x".toByteArray(), null) }
        val r = shellWith(fetcher).execute("gist get a/b", session)
        r.exitCode shouldBe 1
        called shouldBe false
    }

    @Test
    fun `gist reports missing explicit file`() {
        val meta = """
          { "files": { "only.lua": { "filename": "only.lua", "raw_url": "https://gist.githubusercontent.com/x/only.lua" } } }
        """.trimIndent()
        val fetcher = HttpFetcher { url, _, _ ->
            if (url.startsWith("https://api.github.com/gists/")) FetchResult.Ok(meta.toByteArray(), "application/json")
            else error("should not reach file fetch")
        }
        val r = shellWith(fetcher).execute("gist get abc missing.lua", session)
        r.exitCode shouldBe 1
        r.lines.joinToString("\n") shouldContain "no file"
    }
}
