package com.circuitsim.blockentity;

import com.circuitsim.init.ModBlockEntities;
import com.circuitsim.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class ComponentBlockEntity extends BlockEntity {

    private double value = 0.0;
    private String sourceType = "DC";
    private double frequency = 60.0;
    private String label = "";

    public ComponentBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COMPONENT_BE.get(), pos, state);
    }

    public String getComponentType() {
        if (level == null) return "resistor";
        Block block = getBlockState().getBlock();
        if (block == ModBlocks.RESISTOR.get()) return "resistor";
        if (block == ModBlocks.CAPACITOR.get()) return "capacitor";
        if (block == ModBlocks.INDUCTOR.get()) return "inductor";
        if (block == ModBlocks.VOLTAGE_SOURCE.get()) return "voltage_source";
        if (block == ModBlocks.CURRENT_SOURCE.get()) return "current_source";
        if (block == ModBlocks.DIODE.get()) return "diode";
        if (block == ModBlocks.PROBE.get()) return "probe";
        if (block == ModBlocks.SIMULATE.get()) return "simulate";
        return "unknown";
    }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; setChanged(); }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; setChanged(); }
    public double getFrequency() { return frequency; }
    public void setFrequency(double frequency) { this.frequency = frequency; setChanged(); }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; setChanged(); }
    public String getProbeLabel() { return label; }
    public void setProbeLabel(String label) { this.label = label; setChanged(); }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("value")) value = tag.getDouble("value");
        if (tag.contains("sourceType")) sourceType = tag.getString("sourceType");
        if (tag.contains("frequency")) frequency = tag.getDouble("frequency");
        if (tag.contains("label")) label = tag.getString("label");
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putDouble("value", value);
        tag.putString("sourceType", sourceType);
        tag.putDouble("frequency", frequency);
        tag.putString("label", label);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    public void syncToClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}