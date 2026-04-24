package com.brewingcoder.oc2.platform.http

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Production [HttpFetcher] backed by the JDK HttpClient. Uses streaming
 * body-handler + manual read loop so the payload cap is enforced before we
 * allocate a buffer the size of an attacker-controlled response.
 *
 * The HttpClient spawns its own worker threads; the caller (shell command on
 * the server tick) does [java.util.concurrent.Future.get] with a timeout, so
 * the actual socket I/O runs off the server thread even though the call site
 * is synchronous.
 */
object DefaultHttpFetcher : HttpFetcher {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)  // allowlisted URLs only; don't chase redirects
        .build()

    override fun fetch(url: String, timeoutSeconds: Int, maxBytes: Int): FetchResult {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("User-Agent", "OpenComputers2")
            .header("Accept", "*/*")
            .GET()
            .build()

        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        val response = try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            return FetchResult.Err(FetchResult.Err.Kind.TIMEOUT, "request timed out after ${timeoutSeconds}s")
        } catch (t: Throwable) {
            return FetchResult.Err(FetchResult.Err.Kind.NETWORK, t.message ?: "network error")
        }

        val status = response.statusCode()
        if (status !in 200..299) {
            response.body().use { runCatching { it.readNBytes(1024) } }
            return FetchResult.Err(FetchResult.Err.Kind.HTTP_STATUS, "HTTP $status from $url")
        }

        val stream: InputStream = response.body()
        return stream.use { readCapped(it, maxBytes) }?.let {
            FetchResult.Ok(it, response.headers().firstValue("content-type").orElse(null))
        } ?: FetchResult.Err(FetchResult.Err.Kind.TOO_LARGE, "response exceeded ${maxBytes / 1024} KiB cap")
    }

    /** Read up to [cap] bytes from [input]. Returns null if the stream would exceed [cap]. */
    private fun readCapped(input: InputStream, cap: Int): ByteArray? {
        val buf = ByteArray(minOf(cap, 8192).coerceAtLeast(1024))
        val out = java.io.ByteArrayOutputStream(buf.size)
        var total = 0
        while (true) {
            val room = cap - total
            if (room <= 0) {
                // Peek one more byte — if there's more, we're over cap.
                if (input.read() != -1) return null
                break
            }
            val n = input.read(buf, 0, minOf(buf.size, room + 1))
            if (n < 0) break
            if (total + n > cap) return null
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
