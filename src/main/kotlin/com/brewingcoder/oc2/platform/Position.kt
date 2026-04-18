package com.brewingcoder.oc2.platform

/**
 * Platform-layer 3D position. Decoupled from Mojang's [net.minecraft.core.BlockPos]
 * (Rule B in docs/11) so platform code (channel registry, range computation,
 * future driver dispatch) is testable without MC on the classpath.
 *
 * BlockEntity / Entity adapters convert their MC positions to [Position] when
 * exposing themselves through platform interfaces ([ChannelRegistrant], etc.).
 */
data class Position(val x: Int, val y: Int, val z: Int) {

    /** Squared Euclidean distance — cheap comparison vs another position. */
    fun distanceSqTo(other: Position): Long {
        val dx = (x - other.x).toLong()
        val dy = (y - other.y).toLong()
        val dz = (z - other.z).toLong()
        return dx * dx + dy * dy + dz * dz
    }

    /** Returns true if the squared distance to [other] is within [radius] blocks. */
    fun isWithin(other: Position, radius: Int): Boolean {
        val r = radius.toLong()
        return distanceSqTo(other) <= r * r
    }

    override fun toString(): String = "($x, $y, $z)"

    companion object {
        val ORIGIN = Position(0, 0, 0)
    }
}
