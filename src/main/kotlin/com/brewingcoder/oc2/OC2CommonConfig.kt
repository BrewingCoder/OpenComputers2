package com.brewingcoder.oc2

import com.brewingcoder.oc2.block.WiFiExtenderConfig
import com.brewingcoder.oc2.platform.http.NetworkFetchPolicy
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Server/common configuration for OC2. Loaded from
 * `<run>/config/oc2-common.toml` on server start; also read client-side in
 * singleplayer.
 *
 * Values flow one-way from toml → [ModConfigSpec] → [NetworkFetchPolicy]
 * (via [push]). Shell commands read [NetworkFetchPolicy] directly — keeps the
 * Rule-D-pure packages free of MC imports.
 *
 * Registered in [OpenComputers2]'s init block via
 * `ModContainer.registerConfig(ModConfig.Type.COMMON, SPEC)`.
 */
object OC2CommonConfig {

    private val BUILDER = ModConfigSpec.Builder()

    val allowPastebinFetch: ModConfigSpec.BooleanValue = BUILDER
        .comment(
            "Allow the `pastebin` shell command to fetch from pastebin.com/raw/.",
            "Disable on locked-down servers that don't want players pulling arbitrary scripts.",
        )
        .define("network.allowPastebinFetch", true)

    val allowGistFetch: ModConfigSpec.BooleanValue = BUILDER
        .comment(
            "Allow the `gist` shell command to fetch from api.github.com/gists/ + gist.githubusercontent.com.",
        )
        .define("network.allowGistFetch", true)

    val maxFetchBytes: ModConfigSpec.IntValue = BUILDER
        .comment(
            "Maximum payload size in bytes for pastebin/gist fetches.",
            "Default: 1 MiB (1048576). Hard cap: 16 MiB.",
        )
        .defineInRange("network.maxFetchBytes", 1 * 1024 * 1024, 1024, 16 * 1024 * 1024)

    val fetchTimeoutSeconds: ModConfigSpec.IntValue = BUILDER
        .comment(
            "Wall-clock timeout for pastebin/gist fetches, in seconds.",
            "Default: 15. Range: 1..60.",
        )
        .defineInRange("network.fetchTimeoutSeconds", 15, 1, 60)

    val extenderBufferFE: ModConfigSpec.IntValue = BUILDER
        .comment(
            "WiFi Extender internal energy buffer, in FE.",
            "Default: 100000. Range: 1000..10000000.",
        )
        .defineInRange("extender.bufferFE", 100_000, 1_000, 10_000_000)

    val extenderIdleDrawFE: ModConfigSpec.IntValue = BUILDER
        .comment(
            "FE drained per server tick (20 Hz) while a WiFi Extender is active.",
            "Default: 20. Range: 0..1000.",
        )
        .defineInRange("extender.idleDrawFE", 20, 0, 1_000)

    val extenderRangeBlocks: ModConfigSpec.IntValue = BUILDER
        .comment(
            "WiFi Extender broadcast radius in blocks (Euclidean).",
            "Default: 64. Range: 16..256.",
        )
        .defineInRange("extender.rangeBlocks", 64, 16, 256)

    val SPEC: ModConfigSpec = BUILDER.build()

    /**
     * Push current spec values into [NetworkFetchPolicy]. Call on config-loaded
     * + config-reloaded events.
     */
    fun push() {
        NetworkFetchPolicy.allowPastebinFetch = allowPastebinFetch.get()
        NetworkFetchPolicy.allowGistFetch = allowGistFetch.get()
        NetworkFetchPolicy.maxFetchBytes = maxFetchBytes.get()
        NetworkFetchPolicy.fetchTimeoutSeconds = fetchTimeoutSeconds.get()
        WiFiExtenderConfig.bufferFE = extenderBufferFE.get()
        WiFiExtenderConfig.idleDrawFE = extenderIdleDrawFE.get()
        WiFiExtenderConfig.rangeBlocks = extenderRangeBlocks.get()
    }
}
