package com.brewingcoder.oc2.block

/**
 * Runtime holder for WiFi Extender tunables. Mirrors [com.brewingcoder.oc2.platform.http.NetworkFetchPolicy]
 * — OC2CommonConfig.push() fills these on load/reload so the rest of the code
 * (which may be platform-pure) doesn't have to touch NeoForge's config types.
 *
 * Defaults match [com.brewingcoder.oc2.OC2CommonConfig] so tests can run without
 * booting the mod.
 */
object WiFiExtenderConfig {
    /** Energy capacity in FE. Default 100_000. */
    @Volatile var bufferFE: Int = 100_000

    /** FE drained per server tick while active. Default 20. */
    @Volatile var idleDrawFE: Int = 20

    /** Broadcast radius in blocks (Euclidean). Default 64. */
    @Volatile var rangeBlocks: Int = 64
}
