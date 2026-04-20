package com.brewingcoder.oc2.network

import com.brewingcoder.oc2.OpenComputers2
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * Registers OC2's custom network payloads with NeoForge.
 *
 * Each payload gets a version + a registrar binding to a handler. The version
 * lets us evolve packet shapes safely; bumping it requires older clients to
 * reconnect (acceptable for a mod under active dev).
 */
@EventBusSubscriber(modid = OpenComputers2.ID, bus = EventBusSubscriber.Bus.MOD)
object OC2Payloads {

    private const val VERSION = "0"

    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(OpenComputers2.ID).versioned(VERSION)

        registrar.playToServer(
            SetChannelPayload.TYPE,
            SetChannelPayload.STREAM_CODEC,
            SetChannelPayload.Companion::handle,
        )

        registrar.playToServer(
            RunCommandPayload.TYPE,
            RunCommandPayload.STREAM_CODEC,
            RunCommandPayload.Companion::handle,
        )

        registrar.playToServer(
            UpdatePartConfigPayload.TYPE,
            UpdatePartConfigPayload.STREAM_CODEC,
            UpdatePartConfigPayload.Companion::handle,
        )

        registrar.playToServer(
            ComputerControlPayload.TYPE,
            ComputerControlPayload.STREAM_CODEC,
            ComputerControlPayload.Companion::handle,
        )

        registrar.playToServer(
            SetMonitorChannelPayload.TYPE,
            SetMonitorChannelPayload.STREAM_CODEC,
            SetMonitorChannelPayload.Companion::handle,
        )

        registrar.playToClient(
            TerminalOutputPayload.TYPE,
            TerminalOutputPayload.STREAM_CODEC,
            TerminalOutputPayload.Companion::handle,
        )
    }
}
