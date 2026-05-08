package com.circuitsim.network;

import com.circuitsim.blockentity.ComponentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

public class ComponentUpdatePacket {

    private final BlockPos pos;
    private final double   value;
    private final String   sourceType;
    private final double   frequency;
    private final String   label;
    // sky130 resistor fields
    private final String   modelName;
    private final double   wParam;
    private final double   lParam;
    private final double   multParam;

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label) {
        this(pos, value, sourceType, frequency, label, "", 1.0, 1.0, 1.0);
    }

    public ComponentUpdatePacket(BlockPos pos, double value, String sourceType,
                                  double frequency, String label,
                                  String modelName, double wParam, double lParam, double multParam) {
        this.pos        = pos;
        this.value      = value;
        this.sourceType = sourceType;
        this.frequency  = frequency;
        this.label      = label;
        this.modelName  = modelName;
        this.wParam     = wParam;
        this.lParam     = lParam;
        this.multParam  = multParam;
    }

    public ComponentUpdatePacket(FriendlyByteBuf buf) {
        this.pos        = buf.readBlockPos();
        this.value      = buf.readDouble();
        this.sourceType = buf.readUtf(64);
        this.frequency  = buf.readDouble();
        this.label      = buf.readUtf(256);
        this.modelName  = buf.readUtf(128);
        this.wParam     = buf.readDouble();
        this.lParam     = buf.readDouble();
        this.multParam  = buf.readDouble();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeDouble(value);
        buf.writeUtf(sourceType, 64);
        buf.writeDouble(frequency);
        buf.writeUtf(label, 256);
        buf.writeUtf(modelName, 128);
        buf.writeDouble(wParam);
        buf.writeDouble(lParam);
        buf.writeDouble(multParam);
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
            cbe.setModelName(modelName);
            cbe.setWParam(wParam);
            cbe.setLParam(lParam);
            cbe.setMultParam(multParam);
            cbe.setChanged();
            cbe.syncToClient();
        }
    }
}
