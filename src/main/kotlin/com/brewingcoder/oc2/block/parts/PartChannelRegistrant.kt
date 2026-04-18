package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.parts.Part
import com.brewingcoder.oc2.platform.peripheral.Peripheral

/**
 * Wraps each installed [Part] as a [ChannelRegistrant] so the part appears on
 * the wifi channel as its own entry — `peripheral.find("inventory")` returns
 * the inventory part, not the adapter itself.
 *
 * NOT a [Peripheral] directly — the underlying [Part.asPeripheral] is what
 * scripts actually call. The host BE's `findPeripheralOnChannel` translates
 * via [currentPeripheral].
 *
 * Identity equality (default) is what we want: each install is one registrant
 * the registry tracks; remove drops exactly that entry.
 */
class PartChannelRegistrant(
    override var channelId: String,
    override val location: Position,
    val part: Part,
) : ChannelRegistrant {

    override val kind: String get() = part.typeId

    /** Live peripheral handle; re-resolves through the part each call (capability may have come/gone). */
    fun currentPeripheral(): Peripheral? = part.asPeripheral()
}
