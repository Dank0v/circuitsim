package com.circuitsim.network;

import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ComponentUpdatePacket {

    private final BlockPos pos;
    private final double value;
    private final String sourceType;
    private final double frequency;
    private final String label;

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType, double frequency, String label) {
        this.pos = pos;
        this.value = value;
        this.sourceType = sourceType;
        this.frequency = frequency;
        this.label = label;
    }

    public ComponentUpdatePacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.value = buf.readDouble();
        this.sourceType = buf.readUtf(64);
        this.frequency = buf.readDouble();
        this.label = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(value);
        buf.writeUtf(sourceType, 64);
        buf.writeDouble(frequency);
        buf.writeUtf(label, 256);
    }

    public static ComponentUpdatePacket decode(FriendlyByteBuf buf) {
        return new ComponentUpdatePacket(buf);
    }

    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ComponentBlockEntity cbe) {
            cbe.setValue(value);
            cbe.setSourceType(sourceType);
            cbe.setFrequency(frequency);
            cbe.setLabel(label);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}