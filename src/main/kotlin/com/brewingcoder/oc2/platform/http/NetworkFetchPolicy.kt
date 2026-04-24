package com.brewingcoder.oc2.platform.http

/**
 * Process-wide feature-flag + tuning holder for the pastebin/gist commands.
 *
 * Mutable (via @Volatile) so NeoForge config reloads can update the flags
 * without restart. The toml-backed source of truth lives in
 * `com.brewingcoder.oc2.OC2CommonConfig` — this class is Rule-D pure and
 * testable, and the config layer pushes values in on load.
 *
 * Defaults match the v1 spec: both fetchers enabled, 1 MiB cap, 15 s timeout.
 */
object NetworkFetchPolicy {
    @Volatile var allowPastebinFetch: Boolean = true
    @Volatile var allowGistFetch: Boolean = true
    @Volatile var maxFetchBytes: Int = 1 * 1024 * 1024
    @Volatile var fetchTimeoutSeconds: Int = 15
}
