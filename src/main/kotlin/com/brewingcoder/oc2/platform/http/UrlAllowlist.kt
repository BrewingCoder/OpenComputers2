package com.brewingcoder.oc2.platform.http

/**
 * Pure URL builders + input validators for the pastebin / gist shell commands.
 *
 * The shell commands take raw user input (a pastebin key or gist id) and must
 * produce an HTTPS URL pointing at a hard-coded allowlisted host. The allowlist
 * is the URL *shape* itself: there is no way to get [pastebinRaw] to return a
 * URL outside `pastebin.com/raw/`, no way to get [gistApi] outside
 * `api.github.com/gists/`, no way to get [gistRawOnAllowedHost] outside
 * `gist.githubusercontent.com/`. Callers pass the key/id only; the host is a
 * constant.
 *
 * Keys + ids are validated against [isSafeToken]. Anything that could be read
 * as a path component (`/`, `..`, `\`), a query string (`?`, `#`), or URL
 * encoding (`%`) is rejected. Whitespace + the empty string are rejected.
 *
 * This class is Rule-D pure — no MC deps, fully unit-testable.
 */
object UrlAllowlist {

    /** Allowed host for raw pastes. */
    const val PASTEBIN_HOST: String = "pastebin.com"

    /** Allowed host for gist metadata. */
    const val GIST_API_HOST: String = "api.github.com"

    /** Allowed host for gist raw file content (the `raw_url` field on a gist file). */
    const val GIST_RAW_HOST: String = "gist.githubusercontent.com"

    /**
     * Build `https://pastebin.com/raw/<key>`. Returns null if [key] fails
     * [isSafeToken].
     */
    fun pastebinRaw(key: String): String? {
        if (!isSafeToken(key)) return null
        return "https://$PASTEBIN_HOST/raw/$key"
    }

    /**
     * Build `https://api.github.com/gists/<id>`. Returns null if [id] fails
     * [isSafeToken].
     */
    fun gistApi(id: String): String? {
        if (!isSafeToken(id)) return null
        return "https://$GIST_API_HOST/gists/$id"
    }

    /**
     * Validate that a `raw_url` returned from the gist metadata points at the
     * allowed host. Returns the URL unchanged if OK, or null if the host is
     * something else (a GitHub compromise or malicious gist field should not
     * be able to redirect us off to arbitrary servers).
     */
    fun gistRawOnAllowedHost(rawUrl: String): String? {
        if (!rawUrl.startsWith("https://$GIST_RAW_HOST/")) return null
        // No query, no fragment — `raw_url` from the gist API should be clean.
        if (rawUrl.contains('?') || rawUrl.contains('#')) return null
        return rawUrl
    }

    /**
     * True if [s] is a plausible pastebin key / gist id: non-empty, ASCII
     * alphanumeric only (pastebin keys are 8-char alphanumeric; gist ids are
     * 32-char hex). Rejects anything containing URL metacharacters, path
     * separators, or whitespace.
     */
    fun isSafeToken(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.length > 64) return false  // pastebin keys are 8, gist ids 32 — 64 is a generous cap
        return s.all { it in SAFE_CHARS }
    }

    private val SAFE_CHARS: Set<Char> = buildSet {
        for (c in 'a'..'z') add(c)
        for (c in 'A'..'Z') add(c)
        for (c in '0'..'9') add(c)
    }
}
