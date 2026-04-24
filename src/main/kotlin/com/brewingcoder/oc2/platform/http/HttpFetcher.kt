package com.brewingcoder.oc2.platform.http

/**
 * Pluggable HTTP GET. The shell commands take one of these in their ctor so
 * tests can inject a canned-response stub without touching the network.
 *
 * Implementations must:
 *   - execute the HTTP call off the server thread (JDK HttpClient does this
 *     via its own executor; the server-tick caller blocks via Future.get).
 *   - enforce a byte-count cap (returns [FetchResult.Err.Kind.TOO_LARGE] when
 *     the response body exceeds it, without buffering the full payload).
 *   - enforce a wall-clock timeout (returns [FetchResult.Err.Kind.TIMEOUT]).
 *
 * Rule-D pure — no MC deps.
 */
fun interface HttpFetcher {
    /**
     * Synchronous GET on an allowlisted URL. Caller has already validated the
     * URL via [UrlAllowlist]; this method does no additional host checks.
     *
     * @param url fully-qualified https URL from [UrlAllowlist]
     * @param timeoutSeconds wall-clock cap; 15 is the standard value from the
     *   pastebin/gist command spec
     * @param maxBytes payload cap; 1 MiB is the standard value
     */
    fun fetch(url: String, timeoutSeconds: Int, maxBytes: Int): FetchResult
}
