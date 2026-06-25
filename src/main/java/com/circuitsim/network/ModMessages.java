package com.circuitsim.network;

import com.circuitsim.CircuitSimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public class ModMessages {

    private static final String PROTOCOL_VERSION = "12";  // bumped for user-defined subcircuits

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CircuitSimMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        // ── server-bound ──────────────────────────────────────────────────────
        INSTANCE.messageBuilder(ComponentUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ComponentUpdatePacket::encode)
                .decoder(ComponentUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleComponentUpdate)
                .add();

        INSTANCE.messageBuilder(SimulatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SimulatePacket::encode)
                .decoder(SimulatePacket::decode)
                .consumerMainThread(ModMessages::handleSimulate)
                .add();

        INSTANCE.messageBuilder(CommandsUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CommandsUpdatePacket::encode)
                .decoder(CommandsUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleCommandsUpdate)
                .add();

        INSTANCE.messageBuilder(AmplifierUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AmplifierUpdatePacket::encode)
                .decoder(AmplifierUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleAmplifierUpdate)
                .add();

        INSTANCE.messageBuilder(DiscreteNmosUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DiscreteNmosUpdatePacket::encode)
                .decoder(DiscreteNmosUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleDiscreteNmosUpdate)
                .add();

        INSTANCE.messageBuilder(DiscretePmosUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DiscretePmosUpdatePacket::encode)
                .decoder(DiscretePmosUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleDiscretePmosUpdate)
                .add();

        INSTANCE.messageBuilder(DiscreteNpnUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DiscreteNpnUpdatePacket::encode)
                .decoder(DiscreteNpnUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleDiscreteNpnUpdate)
                .add();

        INSTANCE.messageBuilder(DiscretePnpUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DiscretePnpUpdatePacket::encode)
                .decoder(DiscretePnpUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleDiscretePnpUpdate)
                .add();

        INSTANCE.messageBuilder(ControlledSourceUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ControlledSourceUpdatePacket::encode)
                .decoder(ControlledSourceUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleControlledSourceUpdate)
                .add();

        INSTANCE.messageBuilder(VSwitchUpdatePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VSwitchUpdatePacket::encode)
                .decoder(VSwitchUpdatePacket::decode)
                .consumerMainThread(ModMessages::handleVSwitchUpdate)
                .add();

        INSTANCE.messageBuilder(SubcircuitConvertPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SubcircuitConvertPacket::encode)
                .decoder(SubcircuitConvertPacket::decode)
                .consumerMainThread(ModMessages::handleSubcircuitConvert)
                .add();

        // ── client-bound ──────────────────────────────────────────────────────
        INSTANCE.messageBuilder(SimulationResultPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SimulationResultPacket::encode)
                .decoder(SimulationResultPacket::decode)
                .consumerMainThread(ModMessages::handleSimulationResult)
                .add();

        INSTANCE.messageBuilder(GraphDataPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GraphDataPacket::encode)
                .decoder(GraphDataPacket::decode)
                .consumerMainThread(ModMessages::handleGraphData)
                .add();

        INSTANCE.messageBuilder(SimulationOutputPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SimulationOutputPacket::encode)
                .decoder(SimulationOutputPacket::decode)
                .consumerMainThread(ModMessages::handleSimulationOutput)
                .add();

        INSTANCE.messageBuilder(OperatingPointPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OperatingPointPacket::encode)
                .decoder(OperatingPointPacket::decode)
                .consumerMainThread(ModMessages::handleOperatingPoint)
                .add();
    }

    // ── handlers ──────────────────────────────────────────────────────────────

    private static void handleComponentUpdate(ComponentUpdatePacket msg,
                                               Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleSimulate(SimulatePacket msg,
                                        Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleCommandsUpdate(CommandsUpdatePacket msg,
                                              Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleAmplifierUpdate(AmplifierUpdatePacket msg,
                                               Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleDiscreteNmosUpdate(DiscreteNmosUpdatePacket msg,
                                                  Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleDiscretePmosUpdate(DiscretePmosUpdatePacket msg,
                                                  Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleDiscreteNpnUpdate(DiscreteNpnUpdatePacket msg,
                                                 Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleDiscretePnpUpdate(DiscretePnpUpdatePacket msg,
                                                 Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleControlledSourceUpdate(ControlledSourceUpdatePacket msg,
                                                      Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleVSwitchUpdate(VSwitchUpdatePacket msg,
                                             Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleSubcircuitConvert(SubcircuitConvertPacket msg,
                                                 Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }

    private static void handleSimulationResult(SimulationResultPacket msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> msg.handle());
        ctx.get().setPacketHandled(true);
    }

    private static void handleGraphData(GraphDataPacket msg,
                                         Supplier<NetworkEvent.Context> ctx) {
        msg.handle(ctx.get());
    }

    private static void handleSimulationOutput(SimulationOutputPacket msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(msg::handle);
        ctx.get().setPacketHandled(true);
    }

    private static void handleOperatingPoint(OperatingPointPacket msg,
                                              Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(msg::handle);
        ctx.get().setPacketHandled(true);
    }

    // ── send helpers ──────────────────────────────────────────────────────────

    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }

    public static <MSG> void sendToPlayer(ServerPlayer player, MSG message) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}