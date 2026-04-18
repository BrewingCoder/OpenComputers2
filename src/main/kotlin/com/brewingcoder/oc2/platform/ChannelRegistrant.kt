package com.brewingcoder.oc2.platform

/**
 * Anything that can be tracked by [ChannelRegistry]. Defined as a small
 * interface (Rule B in `docs/11-engineering-rules.md`) so the registry has no
 * dependency on Mojang's BlockEntity class — keeps the registry pure and
 * unit-testable.
 *
 * Uses platform-layer [Position] (not Mojang's BlockPos) for the same reason —
 * range computation, registry diagnostics, and tests stay free of MC imports.
 *
 * The registry uses object identity for membership (a Set of registrants), so
 * implementations are responsible for their own equals/hashCode if multiple
 * conceptual identities should collapse. BlockEntity instances naturally have
 * identity equals which is what we want.
 *
 * Future implementations beyond [com.brewingcoder.oc2.block.ComputerBlockEntity]:
 *   - AdapterBlockEntity (when adapters land in R1)
 *   - DroneEntity (R2/R3 — mobile peripheral)
 *   - tests use a simple data-class fake
 */
interface ChannelRegistrant {
    /** The wifi channel this registrant currently belongs to. */
    val channelId: String

    /** World location, for diagnostics and (future) range computation. */
    val location: Position
}
