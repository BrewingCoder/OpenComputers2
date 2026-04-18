package com.brewingcoder.oc2.block.parts

import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.peripheral.Peripheral

/**
 * Resolves a [ChannelRegistrant] from the [com.brewingcoder.oc2.platform.ChannelRegistry]
 * down to the script-facing [Peripheral]. Two flavors of registrant exist today:
 *   - **BE-is-peripheral** (e.g. MonitorBlockEntity implements both ChannelRegistrant + Peripheral)
 *   - **Part-via-adapter** (PartChannelRegistrant holds a Part, which produces the Peripheral)
 *
 * Centralizing the resolution here means lookup callers (Computer, future hosts)
 * don't repeat the `when` branch and won't drift apart.
 */
object PeripheralResolver {
    fun resolve(registrant: ChannelRegistrant): Peripheral? = when (registrant) {
        is PartChannelRegistrant -> registrant.currentPeripheral()
        is Peripheral -> registrant
        else -> null
    }
}
