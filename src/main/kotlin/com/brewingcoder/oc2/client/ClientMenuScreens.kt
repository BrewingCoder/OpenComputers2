package com.brewingcoder.oc2.client

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.client.screen.AdapterPartsScreen
import com.brewingcoder.oc2.client.screen.CrafterScreen
import com.brewingcoder.oc2.client.screen.RecipeProgrammerScreen
import com.brewingcoder.oc2.item.ModMenus
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent

/**
 * Wires AbstractContainerMenu types to their AbstractContainerScreen views.
 * Today: AdapterPartsScreen ↔ ADAPTER_PARTS. Computer + Monitor settings
 * screens are direct [Screen]s, not menu screens, and don't go here.
 *
 * MOD bus, CLIENT-only — same pattern as [ClientRenderers].
 */
@EventBusSubscriber(
    modid = OpenComputers2.ID,
    bus = EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT],
)
object ClientMenuScreens {

    @SubscribeEvent
    fun onRegisterMenuScreens(event: RegisterMenuScreensEvent) {
        event.register(ModMenus.ADAPTER_PARTS.get(), ::AdapterPartsScreen)
        event.register(ModMenus.RECIPE_PROGRAMMER.get(), ::RecipeProgrammerScreen)
        event.register(ModMenus.CRAFTER.get(), ::CrafterScreen)
    }
}
