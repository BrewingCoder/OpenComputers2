package com.brewingcoder.oc2.client

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Client-side configuration for OC2.
 *
 * Loaded from `<run>/config/oc2-client.toml`; the file is auto-created on first
 * launch with the defaults below. Players can edit and the values reload on
 * `/reload` or world re-entry — no game restart required.
 *
 * Registered in [com.brewingcoder.oc2.OpenComputers2]'s init block via
 * `ModContainer.registerConfig(ModConfig.Type.CLIENT, SPEC)`.
 */
object OC2ClientConfig {

    private val BUILDER = ModConfigSpec.Builder()

    /**
     * Max terminal scrollback lines kept per open Computer GUI. Old lines drop
     * off the top once the cap is hit. Range is 50..10000 — anything below 50
     * is unusable, anything above 10000 is a memory-pressure foot-gun.
     */
    val maxTerminalLines: ModConfigSpec.IntValue = BUILDER
        .comment(
            "Maximum number of terminal output lines retained for scrollback per Computer GUI.",
            "Old lines drop off the top once the cap is hit.",
            "Range: 50 .. 10000. Default: 300.",
        )
        .defineInRange("terminal.maxHistoryLines", 300, 50, 10000)

    val SPEC: ModConfigSpec = BUILDER.build()
}
